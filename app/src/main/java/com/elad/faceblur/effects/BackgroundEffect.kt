package com.elad.faceblur.effects

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.elad.faceblur.analysis.MaskStore
import com.elad.faceblur.gl.Gl
import com.elad.faceblur.gl.Quad
import com.elad.faceblur.gl.Uniforms
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** What's kept: the people (found by [com.elad.faceblur.analysis.Segmenter]) or all but a green / blue screen. */
enum class BgKey { PERSON, GREEN, BLUE }

/** What goes behind: nothing changed, the background blurred, a color or a picture. */
enum class BgFill { NONE, BLUR, COLOR, IMAGE }

/**
 * Background removal settings.
 * @param tolerance how far from the screen color still counts as screen (0..1).
 * @param image a picture file (in the app's files) for [BgFill.IMAGE].
 */
data class Background(
    val fill: BgFill = BgFill.NONE,
    val key: BgKey = BgKey.PERSON,
    val color: Int = 0xFF0F1115.toInt(),
    val image: String? = null,
    val tolerance: Float = 0.5f,
) {
    val active get() = fill != BgFill.NONE

    fun toJson(): JSONObject = JSONObject().put("fill", fill.name).put("key", key.name).put("color", color)
        .put("image", image ?: "").put("tolerance", tolerance.toDouble())

    companion object {
        /** Keeps a copy of a picked picture (at most 1920 px) for the background; returns its path. */
        fun importImage(context: Context, uri: android.net.Uri): String? {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            val s = minOf(1f, 1920f / maxOf(bmp.width, bmp.height))
            val small = if (s < 1f) {
                android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            } else {
                bmp
            }
            val dir = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
            val f = java.io.File(dir, "bg_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            return f.absolutePath
        }

        /** A copy of the picture of [b] in the app's backgrounds folder (for a project of its own); [b] as it is if it has none, null picture if it's gone. */
        fun withOwnCopy(context: Context, b: Background): Background {
            val path = b.image ?: return b
            val src = java.io.File(path)
            if (!src.exists()) return b.copy(image = null)
            val dir = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
            val dst = java.io.File.createTempFile("bg_", ".jpg", dir)
            src.copyTo(dst, overwrite = true)
            return b.copy(image = dst.absolutePath)
        }

        fun fromJson(o: JSONObject?): Background {
            if (o == null) return Background()
            return Background(
                runCatching { BgFill.valueOf(o.optString("fill")) }.getOrDefault(BgFill.NONE),
                runCatching { BgKey.valueOf(o.optString("key")) }.getOrDefault(BgKey.PERSON),
                o.optInt("color", 0xFF0F1115.toInt()),
                o.optString("image").ifBlank { null },
                o.optDouble("tolerance", 0.5).toFloat(),
            )
        }
    }
}

/** The settings and person masks the effect reads, per frame (changeable live in the editor). */
class BackgroundState(
    @Volatile var background: Background,
    @Volatile var masks: MaskStore?,
    /** Analysis frame times (µs), to find a frame's mask. */
    val times: LongArray,
)

/** Replaces or blurs the background behind the people (or in front of a green / blue screen). */
class BackgroundEffect(private val state: BackgroundState) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BackgroundProgram(state, useHdr)
}

private class BackgroundProgram(
    private val state: BackgroundState,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
    private var copyProg = 0
    private var prog = 0
    private lateinit var copyU: Uniforms
    private lateinit var u: Uniforms
    private var quad: Quad? = null
    private var w = 0
    private var h = 0
    private var maskTex = 0
    private var maskIndex = -1
    private var maskStore: MaskStore? = null
    private val maskBuf = ByteBuffer.allocateDirect(MaskStore.SIZE * MaskStore.SIZE).order(ByteOrder.nativeOrder())
    private var imageTex = 0
    private var imagePath: String? = null
    private var imageW = 1
    private var imageH = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (prog == 0) {
            copyProg = Gl.program(COPY_FRAGMENT)
            copyU = Uniforms(copyProg)
            prog = Gl.program(FRAGMENT)
            u = Uniforms(prog)
            quad = Quad()
            maskTex = createTexture()
        }
        w = inputWidth
        h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun nearest(times: LongArray, t: Long): Int {
        if (times.isEmpty()) return 0
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        return if (abs(times[hi] - t) < abs(times[lo] - t)) hi else lo
    }

    /** Uploads the mask of the frame at [t]; false if there's none. */
    private fun bindMask(t: Long): Boolean {
        val store = state.masks ?: return false
        val i = nearest(state.times, t)
        if (store !== maskStore || i != maskIndex) {
            val m = store.read(i) ?: return false
            maskBuf.clear()
            maskBuf.put(m)
            maskBuf.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, MaskStore.SIZE, MaskStore.SIZE, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, maskBuf,
            )
            maskStore = store
            maskIndex = i
        }
        return true
    }

    private fun bindImage(path: String?): Boolean {
        if (path == null) return false
        if (path != imagePath) {
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return false
            if (imageTex == 0) imageTex = createTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            imageW = bmp.width
            imageH = bmp.height
            bmp.recycle()
            imagePath = path
        }
        return true
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES20.glDisable(GLES20.GL_BLEND)
        val bg = state.background
        val person = bg.key == BgKey.PERSON
        val ok = bg.active && (!person || bindMask(presentationTimeUs)) && (bg.fill != BgFill.IMAGE || bindImage(bg.image))
        if (!ok) {
            GLES20.glUseProgram(copyProg)
            Gl.bindTexture(0, inputTexId)
            GLES20.glUniform1i(copyU["uSrc"], 0)
            quad!!.draw(copyProg)
            Gl.check("background copy")
            return
        }
        GLES20.glUseProgram(prog)
        Gl.bindTexture(0, inputTexId)
        GLES20.glUniform1i(u["uSrc"], 0)
        Gl.bindTexture(1, maskTex)
        GLES20.glUniform1i(u["uMask"], 1)
        Gl.bindTexture(2, if (imageTex != 0) imageTex else maskTex)
        GLES20.glUniform1i(u["uBg"], 2)
        GLES20.glUniform1i(u["uSource"], if (person) 0 else 1)
        GLES20.glUniform1i(u["uFill"], when (bg.fill) { BgFill.BLUR -> 1; BgFill.COLOR -> 2; else -> 3 })
        GLES20.glUniform1i(u["uKeyChannel"], if (bg.key == BgKey.BLUE) 2 else 1)
        val c = bg.color
        GLES20.glUniform3f(u["uColor"], (c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f)
        // The screen color in CbCr.
        val (kr, kg, kb) = if (bg.key == BgKey.BLUE) Triple(0.1f, 0.25f, 0.85f) else Triple(0.15f, 0.75f, 0.2f)
        GLES20.glUniform2f(u["uKey"], -0.168736f * kr - 0.331264f * kg + 0.5f * kb, 0.5f * kr - 0.418688f * kg - 0.081312f * kb)
        GLES20.glUniform1f(u["uTol"], 0.08f + 0.22f * bg.tolerance)
        // One mask pixel, in the frame's texture coordinates.
        GLES20.glUniform2f(u["uMaskTexel"], 1f / MaskStore.SIZE, 1f / MaskStore.SIZE)
        val r = 0.03f
        GLES20.glUniform2f(u["uRadius"], r, r * w / max(1, h))
        // Picture: cover the frame, centered.
        val s = max(w.toFloat() / imageW, h.toFloat() / imageH)
        val sx = w / (imageW * s)
        val sy = h / (imageH * s)
        GLES20.glUniform4f(u["uBgMap"], sx, sy, (1f - sx) / 2f, (1f - sy) / 2f)
        quad!!.draw(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        Gl.check("background")
    }

    override fun release() {
        super.release()
        Gl.deleteTexture(maskTex)
        Gl.deleteTexture(imageTex)
        maskTex = 0
        imageTex = 0
        quad?.release()
        if (prog != 0) GLES20.glDeleteProgram(prog)
        if (copyProg != 0) GLES20.glDeleteProgram(copyProg)
        prog = 0
        copyProg = 0
    }

    companion object {
        private const val COPY_FRAGMENT = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D uSrc;
void main() { gl_FragColor = texture2D(uSrc, vTex); }
"""

        private const val FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
uniform sampler2D uMask;
uniform sampler2D uBg;
uniform int uSource;
uniform int uFill;
uniform int uKeyChannel;
uniform vec3 uColor;
uniform vec2 uKey;
uniform float uTol;
uniform vec2 uRadius;
uniform vec4 uBgMap;
uniform vec2 uMaskTexel;

vec2 cbcr(vec3 c) {
    return vec2(-0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b, 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b);
}

// How much of the point is foreground (person, or not screen): quick, for the blur's samples.
float fgAlpha(vec2 uv) {
    if (uSource == 0) return smoothstep(0.3, 0.7, texture2D(uMask, vec2(uv.x, 1.0 - uv.y)).r);
    float d = distance(cbcr(texture2D(uSrc, uv).rgb), uKey);
    return smoothstep(uTol, uTol + 0.08, d);
}

// The person mask is coarse: around each point it's averaged over neighbours that look alike
// (joint bilateral), so its edge snaps to the picture's own edges.
float personAlpha(vec2 uv) {
    vec3 c0 = texture2D(uSrc, uv).rgb;
    float acc = 0.0;
    float wsum = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 o = vec2(float(i), float(j)) * uMaskTexel;
            float m = texture2D(uMask, vec2(uv.x + o.x, 1.0 - uv.y - o.y)).r;
            vec3 c = texture2D(uSrc, uv + o).rgb;
            vec3 dc = c - c0;
            float w = exp(-dot(dc, dc) * 40.0 - float(i * i + j * j) * 0.3);
            acc += m * w;
            wsum += w;
        }
    }
    return smoothstep(0.38, 0.62, acc / wsum);
}

void main() {
    vec4 src = texture2D(uSrc, vTex);
    float a = uSource == 0 ? personAlpha(vTex) : fgAlpha(vTex);
    vec3 fg = src.rgb;
    if (uSource == 1) {
        // Spill: the screen's color reflected on the subject is taken out.
        if (uKeyChannel == 1) fg.g = min(fg.g, max(fg.r, fg.b));
        else fg.b = min(fg.b, max(fg.r, fg.g));
    }
    vec3 bg;
    if (uFill == 2) {
        bg = uColor;
    } else if (uFill == 3) {
        bg = texture2D(uBg, vec2(vTex.x, 1.0 - vTex.y) * uBgMap.xy + uBgMap.zw).rgb;
    } else {
        // A disc of samples from the background only, so the person leaves no halo.
        vec3 acc = vec3(0.0);
        float wsum = 0.0;
        for (int i = 0; i < 28; i++) {
            float fi = float(i);
            float r = sqrt((fi + 0.5) / 28.0);
            float th = fi * 2.39996;
            vec2 uv = clamp(vTex + vec2(cos(th), sin(th)) * r * uRadius, 0.0, 1.0);
            float wgt = 1.0 - fgAlpha(uv) + 0.03;
            acc += texture2D(uSrc, uv).rgb * wgt;
            wsum += wgt;
        }
        bg = acc / wsum;
    }
    gl_FragColor = vec4(mix(bg, fg, a), src.a);
}
"""
    }
}
