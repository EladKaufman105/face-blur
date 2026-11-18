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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class BlurMode { BLUR, PIXELATE, SOLID }

/**
 * Render pass effect: covers every face from [timeline] in each frame.
 *
 * Blur: each face's surroundings are copied (anti-aliased, via a box-filtered pyramid) into a
 * 128x128 slot of an atlas, Gaussian-blurred there in two separable passes, and composited
 * back through a soft, rotated ellipse. The blur radius is relative to the face size, so a
 * close-up gets as anonymized as a face in the background.
 *
 * Pixelate: same machinery with a smaller blur, sampled once per mosaic block.
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
    private val highPrecision: Boolean,
) : BaseGlShaderProgram(highPrecision, /* texturePoolCapacity= */ 1) {

    private var copyProg = 0
    private var downProg = 0
    private var roiProg = 0
    private var gaussProg = 0
    private var compProg = 0
    private lateinit var copyU: Uniforms
    private lateinit var downU: Uniforms
    private lateinit var roiU: Uniforms
    private lateinit var gaussU: Uniforms
    private lateinit var compU: Uniforms
    private var quad: Quad? = null

    private var w = 0
    private var h = 0
    private val levelTex = IntArray(MAX_LEVELS + 1)
    private val levelFbo = IntArray(MAX_LEVELS + 1)
    private val levelW = IntArray(MAX_LEVELS + 1)
    private val levelH = IntArray(MAX_LEVELS + 1)
    private val atlasTex = IntArray(2)
    private val atlasFbo = IntArray(2)
    private val tempTex = IntArray(2)
    private val tempFbo = IntArray(2)
    private val target = Gl.SavedTarget()

    private val faceArr = FloatArray(CHUNK * 4)
    private val rotArr = FloatArray(CHUNK * 4)
    private val slotArr = FloatArray(CHUNK * 4)
    private val alphaArr = FloatArray(CHUNK)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (compProg == 0) {
            copyProg = Gl.program(COPY_FRAGMENT); copyU = Uniforms(copyProg)
            downProg = Gl.program(DOWNSAMPLE_FRAGMENT); downU = Uniforms(downProg)
            roiProg = Gl.program(ROI_FRAGMENT); roiU = Uniforms(roiProg)
            gaussProg = Gl.program(GAUSS_FRAGMENT); gaussU = Uniforms(gaussProg)
            compProg = Gl.program(COMPOSITE_FRAGMENT); compU = Uniforms(compProg)
            quad = Quad()
            for (i in 0..1) {
                atlasTex[i] = Gl.createTexture(ATLAS, ATLAS, highPrecision)
                atlasFbo[i] = Gl.createFbo(atlasTex[i])
            }
        }
        if (inputWidth != w || inputHeight != h) {
            releaseFrameTargets()
            w = inputWidth
            h = inputHeight
            levelW[0] = w
            levelH[0] = h
            for (k in 1..MAX_LEVELS) {
                levelW[k] = max(1, (levelW[k - 1] + 1) / 2)
                levelH[k] = max(1, (levelH[k - 1] + 1) / 2)
            }
        }
        return Size(inputWidth, inputHeight)
    }

    private fun ensureLevel(k: Int) {
        if (levelTex[k] == 0) {
            levelTex[k] = Gl.createTexture(levelW[k], levelH[k], highPrecision)
            levelFbo[k] = Gl.createFbo(levelTex[k])
        }
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

        // Per-face geometry in pixels, GL orientation (origin bottom-left, y up).
        val n = faces.size
        val cx = FloatArray(n); val cy = FloatArray(n)
        val rx = FloatArray(n); val ry = FloatArray(n)
        val co = FloatArray(n); val si = FloatArray(n)
        val roiR = FloatArray(n); val sigma = FloatArray(n); val block = FloatArray(n)
        val level = IntArray(n)
        val alpha = FloatArray(n)
        var maxLevel = 0
        for (i in 0 until n) {
            val f = faces[i]
            alpha[i] = f.alpha
            cx[i] = f.cx * w
            cy[i] = (1f - f.cy) * h
            rx[i] = max(2f, f.rx * w)
            ry[i] = max(2f, f.ry * w)
            val glAngle = -f.angle
            co[i] = cos(glAngle); si[i] = sin(glAngle)
            val size = 2f * max(rx[i], ry[i])
            when (mode) {
                BlurMode.BLUR -> {
                    sigma[i] = size * (0.035f + 0.075f * strength)
                    block[i] = 0f
                }
                BlurMode.PIXELATE -> {
                    block[i] = max(2f, size / (16f - 9f * strength))
                    sigma[i] = block[i] * 0.4f
                }
                BlurMode.SOLID -> {
                    sigma[i] = 0f; block[i] = 0f
                }
            }
            roiR[i] = max(rx[i], ry[i]) * MASK_OUTER + 3f * sigma[i] + 2f
            val s = 2f * roiR[i] / SLOT
            level[i] = if (s <= 1f) 0 else min(MAX_LEVELS, floor(log2(s)).toInt())
            maxLevel = max(maxLevel, level[i])
        }

        // Box-filtered pyramid, only as deep as this frame needs.
        if (mode != BlurMode.SOLID && maxLevel > 0) {
            GLES20.glUseProgram(downProg)
            for (k in 1..maxLevel) {
                ensureLevel(k)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, levelFbo[k])
                GLES20.glViewport(0, 0, levelW[k], levelH[k])
                Gl.bindTexture(0, if (k == 1) inputTexId else levelTex[k - 1])
                GLES20.glUniform1i(downU["uSrc"], 0)
                GLES20.glUniform2f(downU["uSrcTexel"], 1f / levelW[k - 1], 1f / levelH[k - 1])
                quad!!.draw(downProg)
            }
            Gl.check("pyramid")
        }

        val chunks = (n + CHUNK - 1) / CHUNK
        if (chunks > 1 && tempTex[0] == 0) {
            for (i in 0..1) {
                tempTex[i] = Gl.createTexture(w, h, highPrecision)
                tempFbo[i] = Gl.createFbo(tempTex[i])
            }
        }
        var base = inputTexId
        for (c in 0 until chunks) {
            val from = c * CHUNK
            val count = min(CHUNK, n - from)

            if (mode != BlurMode.SOLID) {
                // 1. Copy each face's surroundings into its atlas slot.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, atlasFbo[0])
                GLES20.glUseProgram(roiProg)
                GLES20.glUniform1i(roiU["uSrc"], 0)
                for (j in 0 until count) {
                    val i = from + j
                    slotViewport(j)
                    Gl.bindTexture(0, if (level[i] == 0) inputTexId else levelTex[level[i]])
                    GLES20.glUniform4f(
                        roiU["uRoi"],
                        (cx[i] - roiR[i]) / w, (cy[i] - roiR[i]) / h,
                        (cx[i] + roiR[i]) / w, (cy[i] + roiR[i]) / h,
                    )
                    quad!!.draw(roiProg)
                }
                // 2. Separable Gaussian, horizontal then vertical, inside each slot.
                GLES20.glUseProgram(gaussProg)
                GLES20.glUniform1i(gaussU["uSrc"], 0)
                for (pass in 0..1) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, atlasFbo[1 - pass])
                    Gl.bindTexture(0, atlasTex[pass])
                    if (pass == 0) GLES20.glUniform2f(gaussU["uDir"], 1f / ATLAS, 0f)
                    else GLES20.glUniform2f(gaussU["uDir"], 0f, 1f / ATLAS)
                    for (j in 0 until count) {
                        val i = from + j
                        slotViewport(j)
                        val (u0, v0, u1, v1) = slotUv(j)
                        GLES20.glUniform4f(gaussU["uSlot"], u0, v0, u1, v1)
                        val half = 0.5f / ATLAS
                        GLES20.glUniform4f(gaussU["uClamp"], u0 + half, v0 + half, u1 - half, v1 - half)
                        val sigmaSlot = sigma[i] * SLOT / (2f * roiR[i])
                        GLES20.glUniform1f(gaussU["uSigma"], max(0.3f, min(MAX_SIGMA, sigmaSlot)))
                        quad!!.draw(gaussProg)
                    }
                }
                Gl.check("atlas")
            }

            // 3. Composite.
            if (c == chunks - 1) {
                target.restore()
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tempFbo[c % 2])
                GLES20.glViewport(0, 0, w, h)
            }
            for (j in 0 until count) {
                val i = from + j
                faceArr[j * 4] = cx[i]; faceArr[j * 4 + 1] = cy[i]
                faceArr[j * 4 + 2] = rx[i]; faceArr[j * 4 + 3] = ry[i]
                rotArr[j * 4] = co[i]; rotArr[j * 4 + 1] = si[i]
                rotArr[j * 4 + 2] = roiR[i]; rotArr[j * 4 + 3] = block[i]
                val (u0, v0, u1, v1) = slotUv(j)
                slotArr[j * 4] = u0; slotArr[j * 4 + 1] = v0
                slotArr[j * 4 + 2] = u1; slotArr[j * 4 + 3] = v1
                alphaArr[j] = alpha[i]
            }
            GLES20.glUseProgram(compProg)
            Gl.bindTexture(0, base)
            GLES20.glUniform1i(compU["uSrc"], 0)
            Gl.bindTexture(1, atlasTex[0])
            GLES20.glUniform1i(compU["uAtlas"], 1)
            GLES20.glUniform2f(compU["uSize"], w.toFloat(), h.toFloat())
            GLES20.glUniform1i(compU["uCount"], count)
            GLES20.glUniform1i(compU["uMode"], mode.ordinal)
            GLES20.glUniform2f(compU["uMask"], MASK_INNER, MASK_OUTER)
            GLES20.glUniform1fv(compU["uAlpha"], count, alphaArr, 0)
            GLES20.glUniform4fv(compU["uFace"], count, faceArr, 0)
            GLES20.glUniform4fv(compU["uRot"], count, rotArr, 0)
            GLES20.glUniform4fv(compU["uSlot"], count, slotArr, 0)
            quad!!.draw(compProg)
            Gl.bindTexture(1, 0)
            Gl.bindTexture(0, 0)
            base = tempTex[c % 2]
        }
        Gl.check("composite")
    }

    private fun slotViewport(j: Int) {
        GLES20.glViewport((j % COLS) * SLOT, (j / COLS) * SLOT, SLOT, SLOT)
    }

    private data class Uv(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private fun slotUv(j: Int): Uv {
        val x = (j % COLS) * SLOT.toFloat()
        val y = (j / COLS) * SLOT.toFloat()
        return Uv(x / ATLAS, y / ATLAS, (x + SLOT) / ATLAS, (y + SLOT) / ATLAS)
    }

    private fun releaseFrameTargets() {
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
        releaseFrameTargets()
        for (i in 0..1) {
            Gl.deleteFbo(atlasFbo[i]); Gl.deleteTexture(atlasTex[i])
            atlasFbo[i] = 0; atlasTex[i] = 0
        }
        quad?.release()
        for (p in intArrayOf(copyProg, downProg, roiProg, gaussProg, compProg)) if (p != 0) GLES20.glDeleteProgram(p)
        copyProg = 0; downProg = 0; roiProg = 0; gaussProg = 0; compProg = 0
    }

    companion object {
        private const val MAX_LEVELS = 7
        private const val SLOT = 128
        private const val COLS = 4
        private const val CHUNK = 16
        private const val ATLAS = SLOT * COLS // 4x4 slots
        // Soft mask: full effect inside 0.7 of the ellipse radius, fading out to 1.15.
        private const val MASK_INNER = 0.7f
        private const val MASK_OUTER = 1.15f
        private const val MAX_SIGMA = 8f

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

        private const val ROI_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
uniform vec4 uRoi;
void main() {
    gl_FragColor = texture2D(uSrc, mix(uRoi.xy, uRoi.zw, vTex));
}
"""

        private const val GAUSS_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
uniform vec2 uDir;
uniform vec4 uSlot;
uniform vec4 uClamp;
uniform float uSigma;
void main() {
    vec2 uv = mix(uSlot.xy, uSlot.zw, vTex);
    float k = -0.5 / (uSigma * uSigma);
    float reach = 3.0 * uSigma + 0.5;
    vec4 acc = texture2D(uSrc, uv);
    float total = 1.0;
    for (int i = 1; i <= 25; i++) {
        float fi = float(i);
        if (fi > reach) break;
        float wt = exp(fi * fi * k);
        vec2 o = uDir * fi;
        acc += (texture2D(uSrc, clamp(uv + o, uClamp.xy, uClamp.zw))
              + texture2D(uSrc, clamp(uv - o, uClamp.xy, uClamp.zw))) * wt;
        total += 2.0 * wt;
    }
    gl_FragColor = acc / total;
}
"""

        // uFace: center + half-axes (px). uRot: cos, sin, ROI half-size, mosaic block (px).
        // uSlot: the face's atlas rectangle (uv).
        private const val COMPOSITE_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
uniform sampler2D uAtlas;
uniform vec2 uSize;
uniform int uCount;
uniform int uMode;
uniform vec2 uMask;
uniform float uAlpha[16];
uniform vec4 uFace[16];
uniform vec4 uRot[16];
uniform vec4 uSlot[16];

float ellipseDist(vec2 d, vec4 f, vec4 r) {
    vec2 q = vec2(d.x * r.x + d.y * r.y, -d.x * r.y + d.y * r.x) / f.zw;
    return length(q);
}

vec3 atlasAt(vec2 d, vec4 r, vec4 slot) {
    vec2 q = clamp((d + r.z) / (2.0 * r.z), 0.0, 1.0);
    return texture2D(uAtlas, mix(slot.xy, slot.zw, q)).rgb;
}

void main() {
    vec4 base = texture2D(uSrc, vTex);
    vec3 col = base.rgb;
    vec2 p = vTex * uSize;
    for (int i = 0; i < 16; i++) {
        if (i >= uCount) break;
        vec4 f = uFace[i];
        vec4 r = uRot[i];
        vec2 d = p - f.xy;
        if (abs(d.x) > r.z || abs(d.y) > r.z) continue;
        float fade = uAlpha[i];
        if (uMode == 1) {
            // Mosaic: blocks anchored on the face center; a block is in if its center is.
            vec2 bd = (floor(d / r.w) + 0.5) * r.w;
            if (ellipseDist(bd, f, r) <= 1.0) col = mix(col, atlasAt(bd, r, uSlot[i]), fade);
        } else if (uMode == 2) {
            float a = 1.0 - smoothstep(0.96, 1.02, ellipseDist(d, f, r));
            col = mix(col, vec3(0.0), a * fade);
        } else {
            float a = 1.0 - smoothstep(uMask.x, uMask.y, ellipseDist(d, f, r));
            if (a > 0.0) col = mix(col, atlasAt(d, r, uSlot[i]), a * fade);
        }
    }
    gl_FragColor = vec4(col, base.a);
}
"""
    }
}
