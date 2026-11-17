package com.elad.faceblur.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.elad.faceblur.gl.Gl
import com.elad.faceblur.gl.Quad
import com.elad.faceblur.gl.Uniforms
import com.elad.faceblur.track.FaceTimeline
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class BlurMode(val shaderValue: Int) { BLUR(0), PIXELATE(1), SOLID(2) }

/**
 * Render pass effect: covers every face from [timeline] in each frame.
 *
 * Blur/pixelate read from a per-frame image pyramid (each level = 4x4 box downsample of the
 * previous), choosing the level from the face's size, so the effect strength is relative to
 * the face — a close-up gets as anonymized as a face in the background.
 */
class BlurEffect(
    private val timeline: FaceTimeline,
    private val mode: BlurMode,
    private val strength: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BlurProgram(timeline, mode, strength, useHdr)
}

private class BlurProgram(
    private val timeline: FaceTimeline,
    private val mode: BlurMode,
    private val strength: Float,
    private val useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private var copyProg = 0
    private var downProg = 0
    private var compProg = 0
    private lateinit var copyU: Uniforms
    private lateinit var downU: Uniforms
    private lateinit var compU: Uniforms
    private var quad: Quad? = null

    private var w = 0
    private var h = 0
    private val levelTex = IntArray(MAX_LEVELS + 1)
    private val levelFbo = IntArray(MAX_LEVELS + 1)
    private val levelW = IntArray(MAX_LEVELS + 1)
    private val levelH = IntArray(MAX_LEVELS + 1)
    private val tempTex = IntArray(2)
    private val tempFbo = IntArray(2)
    private val target = Gl.SavedTarget()

    private val faceArr = FloatArray(CHUNK * 4)
    private val paramArr = FloatArray(CHUNK * 4)
    private val texelArr = FloatArray((MAX_LEVELS + 1) * 2)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (compProg == 0) {
            copyProg = Gl.program(COPY_FRAGMENT)
            downProg = Gl.program(DOWNSAMPLE_FRAGMENT)
            compProg = Gl.program(COMPOSITE_FRAGMENT)
            copyU = Uniforms(copyProg)
            downU = Uniforms(downProg)
            compU = Uniforms(compProg)
            quad = Quad()
        }
        if (inputWidth != w || inputHeight != h) {
            releaseTargets()
            w = inputWidth
            h = inputHeight
            levelW[0] = w
            levelH[0] = h
            for (k in 1..MAX_LEVELS) {
                levelW[k] = max(1, (levelW[k - 1] + 1) / 2)
                levelH[k] = max(1, (levelH[k - 1] + 1) / 2)
                levelTex[k] = Gl.createTexture(levelW[k], levelH[k], useHdr)
                levelFbo[k] = Gl.createFbo(levelTex[k])
            }
            for (k in 0..MAX_LEVELS) {
                texelArr[k * 2] = 1f / levelW[k]
                texelArr[k * 2 + 1] = 1f / levelH[k]
            }
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val faces = timeline.facesAt(presentationTimeUs)
        GLES20.glDisable(GLES20.GL_BLEND)

        if (faces.isEmpty()) {
            GLES20.glUseProgram(copyProg)
            Gl.bindTexture(0, inputTexId)
            GLES20.glUniform1i(copyU["uSrc"], 0)
            quad!!.draw(copyProg)
            Gl.check("copy")
            return
        }

        target.save()

        // Per-face parameters in pixels (bottom-left origin, as GL textures are laid out).
        val cells = lerp(16f, 5f, strength)      // blur: resolution cells left across a face
        val blocks = lerp(16f, 6f, strength)     // pixelate: mosaic blocks across a face
        val px = FloatArray(faces.size * 4)
        val pr = FloatArray(faces.size * 4)
        var maxLod = 0f
        faces.forEachIndexed { i, f ->
            val cx = f.cx * w
            val cy = (1f - f.cy) * h
            val rx = max(2f, f.rx * w)
            val ry = max(2f, f.ry * h)
            val size = 2f * max(rx, ry)
            val lod: Float
            val blockPx: Float
            when (mode) {
                BlurMode.BLUR -> {
                    lod = max(1f, log2(size / cells)); blockPx = 1f
                }
                BlurMode.PIXELATE -> {
                    blockPx = max(2f, size / blocks); lod = max(0f, log2(blockPx) - 0.5f)
                }
                BlurMode.SOLID -> {
                    lod = 0f; blockPx = 1f
                }
            }
            maxLod = max(maxLod, lod)
            px[i * 4] = cx; px[i * 4 + 1] = cy; px[i * 4 + 2] = rx; px[i * 4 + 3] = ry
            pr[i * 4] = lod; pr[i * 4 + 1] = blockPx
        }

        // Build only the pyramid levels this frame needs.
        val levels = if (mode == BlurMode.SOLID) 1 else min(MAX_LEVELS, ceil(maxLod).toInt() + 1)
        GLES20.glUseProgram(downProg)
        for (k in 1..levels) {
            val src = if (k == 1) inputTexId else levelTex[k - 1]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, levelFbo[k])
            GLES20.glViewport(0, 0, levelW[k], levelH[k])
            Gl.bindTexture(0, src)
            GLES20.glUniform1i(downU["uSrc"], 0)
            GLES20.glUniform2f(downU["uSrcTexel"], 1f / levelW[k - 1], 1f / levelH[k - 1])
            quad!!.draw(downProg)
        }
        Gl.check("pyramid")

        // Composite, CHUNK faces per pass; extra passes ping-pong through temp textures.
        val chunkCount = (faces.size + CHUNK - 1) / CHUNK
        if (chunkCount > 1 && tempTex[0] == 0) {
            for (i in 0..1) {
                tempTex[i] = Gl.createTexture(w, h, useHdr)
                tempFbo[i] = Gl.createFbo(tempTex[i])
            }
        }
        var base = inputTexId
        GLES20.glUseProgram(compProg)
        for (c in 0 until chunkCount) {
            val lastChunk = c == chunkCount - 1
            if (lastChunk) {
                target.restore()
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tempFbo[c % 2])
                GLES20.glViewport(0, 0, w, h)
            }
            val from = c * CHUNK
            val n = min(CHUNK, faces.size - from)
            System.arraycopy(px, from * 4, faceArr, 0, n * 4)
            System.arraycopy(pr, from * 4, paramArr, 0, n * 4)

            Gl.bindTexture(0, base)
            GLES20.glUniform1i(compU["uL0"], 0)
            for (k in 1..MAX_LEVELS) {
                Gl.bindTexture(k, levelTex[min(k, levels)])
                GLES20.glUniform1i(compU["uL$k"], k)
            }
            GLES20.glUniform2fv(compU["uTexel"], MAX_LEVELS + 1, texelArr, 0)
            GLES20.glUniform2f(compU["uSize"], w.toFloat(), h.toFloat())
            GLES20.glUniform1i(compU["uCount"], n)
            GLES20.glUniform1i(compU["uMode"], mode.shaderValue)
            GLES20.glUniform1i(compU["uMaxLevel"], levels)
            GLES20.glUniform1f(compU["uFeather"], if (mode == BlurMode.BLUR) 0.14f else 0.03f)
            GLES20.glUniform4fv(compU["uFace"], n, faceArr, 0)
            GLES20.glUniform4fv(compU["uParam"], n, paramArr, 0)
            quad!!.draw(compProg)
            base = tempTex[c % 2]
        }
        Gl.bindTexture(0, 0)
        Gl.check("composite")
    }

    private fun releaseTargets() {
        for (k in 1..MAX_LEVELS) {
            Gl.deleteFbo(levelFbo[k]); Gl.deleteTexture(levelTex[k])
            levelFbo[k] = 0; levelTex[k] = 0
        }
        for (i in 0..1) {
            Gl.deleteFbo(tempFbo[i]); Gl.deleteTexture(tempTex[i])
            tempFbo[i] = 0; tempTex[i] = 0
        }
    }

    override fun release() {
        super.release()
        releaseTargets()
        quad?.release()
        for (p in intArrayOf(copyProg, downProg, compProg)) if (p != 0) GLES20.glDeleteProgram(p)
        copyProg = 0; downProg = 0; compProg = 0
    }

    companion object {
        private const val MAX_LEVELS = 7
        private const val CHUNK = 32

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
        private fun log2(v: Float) = (ln(v.toDouble()) / ln(2.0)).toFloat()

        private const val COPY_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
void main() {
    gl_FragColor = texture2D(uSrc, vTex);
}
"""

        // 4 bilinear taps at +-1 source texel = 4x4 box filter, halving the size.
        private const val DOWNSAMPLE_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
uniform vec2 uSrcTexel;
void main() {
    vec4 c = texture2D(uSrc, vTex + vec2(-uSrcTexel.x, -uSrcTexel.y))
           + texture2D(uSrc, vTex + vec2( uSrcTexel.x, -uSrcTexel.y))
           + texture2D(uSrc, vTex + vec2(-uSrcTexel.x,  uSrcTexel.y))
           + texture2D(uSrc, vTex + vec2( uSrcTexel.x,  uSrcTexel.y));
    gl_FragColor = c * 0.25;
}
"""

        private const val COMPOSITE_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uL0;
uniform sampler2D uL1;
uniform sampler2D uL2;
uniform sampler2D uL3;
uniform sampler2D uL4;
uniform sampler2D uL5;
uniform sampler2D uL6;
uniform sampler2D uL7;
uniform vec2 uTexel[8];
uniform vec2 uSize;
uniform int uCount;
uniform int uMode;
uniform int uMaxLevel;
uniform float uFeather;
uniform vec4 uFace[32];
uniform vec4 uParam[32];

vec4 tap(int l, vec2 uv) {
    if (l <= 0) return texture2D(uL0, uv);
    if (l == 1) return texture2D(uL1, uv);
    if (l == 2) return texture2D(uL2, uv);
    if (l == 3) return texture2D(uL3, uv);
    if (l == 4) return texture2D(uL4, uv);
    if (l == 5) return texture2D(uL5, uv);
    if (l == 6) return texture2D(uL6, uv);
    return texture2D(uL7, uv);
}

vec2 texelOf(int l) {
    if (l <= 0) return uTexel[0];
    if (l == 1) return uTexel[1];
    if (l == 2) return uTexel[2];
    if (l == 3) return uTexel[3];
    if (l == 4) return uTexel[4];
    if (l == 5) return uTexel[5];
    if (l == 6) return uTexel[6];
    return uTexel[7];
}

vec4 tent(int l, vec2 uv, float spread) {
    vec2 t = texelOf(l) * spread;
    vec4 c = tap(l, uv) * 4.0;
    c += (tap(l, uv + vec2(t.x, 0.0)) + tap(l, uv - vec2(t.x, 0.0))
        + tap(l, uv + vec2(0.0, t.y)) + tap(l, uv - vec2(0.0, t.y))) * 2.0;
    c += tap(l, uv + t) + tap(l, uv - t)
       + tap(l, uv + vec2(t.x, -t.y)) + tap(l, uv + vec2(-t.x, t.y));
    return c * 0.0625;
}

vec4 blurAt(float lod, vec2 uv, float spread) {
    float maxL = float(uMaxLevel);
    if (lod >= maxL) {
        return tent(uMaxLevel, uv, spread * exp2(min(lod - maxL, 3.0)));
    }
    float l0 = floor(lod);
    int i0 = int(l0);
    return mix(tent(i0, uv, spread), tent(i0 + 1, uv, spread), lod - l0);
}

void main() {
    vec4 orig = texture2D(uL0, vTex);
    vec2 p = vTex * uSize;
    float best = 0.0;
    vec4 bf = vec4(0.0);
    vec4 bp = vec4(1.0);
    for (int i = 0; i < 32; i++) {
        if (i >= uCount) break;
        vec4 f = uFace[i];
        vec4 q = uParam[i];
        float a;
        if (uMode == 1) {
            vec2 bc = f.xy + (floor((p - f.xy) / q.y) + 0.5) * q.y;
            a = length((bc - f.xy) / f.zw) <= 1.0 ? 1.0 : 0.0;
        } else {
            float d = length((p - f.xy) / f.zw);
            a = 1.0 - smoothstep(1.0 - uFeather, 1.0, d);
        }
        if (a > best) { best = a; bf = f; bp = q; }
    }
    if (best <= 0.0) {
        gl_FragColor = orig;
        return;
    }
    vec3 col;
    if (uMode == 2) {
        col = vec3(0.0);
    } else if (uMode == 1) {
        vec2 bc = bf.xy + (floor((p - bf.xy) / bp.y) + 0.5) * bp.y;
        col = blurAt(bp.x, bc / uSize, 0.5).rgb;
    } else {
        col = blurAt(bp.x, vTex, 1.0).rgb;
    }
    gl_FragColor = vec4(mix(orig.rgb, col, best), orig.a);
}
"""
    }
}
