package com.elad.faceblur.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.elad.faceblur.analysis.FaceAnalyzer
import com.elad.faceblur.gl.Gl
import com.elad.faceblur.gl.Quad
import com.elad.faceblur.gl.Uniforms
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Analysis pass effect: downsamples every frame, reads it back and hands it to the
 * [FaceAnalyzer]: at the detection size (1280) for frames faces are detected on, else only at
 * the camera-motion size (640), a quarter of the pixels to read back and handle. Its own video
 * output is a small black frame (the analysis file is discarded).
 */
class DetectEffect(
    private val analyzer: FaceAnalyzer,
    private val metadataRotation: Int,
    private val displayLandscape: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DetectProgram(analyzer, metadataRotation, displayLandscape, useHdr)
}

private class DetectProgram(
    private val analyzer: FaceAnalyzer,
    private val metadataRotation: Int,
    private val displayLandscape: Boolean,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private var program = 0
    private lateinit var uniforms: Uniforms
    private var quad: Quad? = null

    private var inW = 0
    private var inH = 0
    private var detW = 0
    private var detH = 0
    private var tex = 0
    private var fbo = 0
    private var pixels: ByteBuffer? = null
    // The motion-only target (0 when the frames are already that small).
    private var smallW = 0
    private var smallH = 0
    private var smallTex = 0
    private var smallFbo = 0
    private var smallPixels: ByteBuffer? = null
    private var rotationToApply = 0
    private val target = Gl.SavedTarget()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (program == 0) {
            program = Gl.program(DOWNSCALE_FLIP_FRAGMENT)
            uniforms = Uniforms(program)
            quad = Quad()
        }
        if (inputWidth != inW || inputHeight != inH) {
            releaseTarget()
            inW = inputWidth
            inH = inputHeight
            val scale = minOf(1f, analyzer.maxSide.toFloat() / max(inputWidth, inputHeight))
            detW = max(16, (inputWidth * scale).roundToInt())
            detH = max(16, (inputHeight * scale).roundToInt())
            tex = Gl.createTexture(detW, detH, highPrecision = false)
            fbo = Gl.createFbo(tex)
            pixels = ByteBuffer.allocateDirect(detW * detH * 4).order(ByteOrder.nativeOrder())
            val (sw, sh) = analyzer.motionSize(detW, detH)
            if (sw < detW && sh < detH) {
                smallW = sw
                smallH = sh
                smallTex = Gl.createTexture(sw, sh, highPrecision = false)
                smallFbo = Gl.createFbo(smallTex)
                smallPixels = ByteBuffer.allocateDirect(sw * sh * 4).order(ByteOrder.nativeOrder())
            }

            // Media3 normally hands effects display-oriented frames. If it didn't (frame
            // aspect disagrees with the display aspect), rotate before detection.
            val pipelineLandscape = inputWidth >= inputHeight
            rotationToApply =
                if ((metadataRotation == 90 || metadataRotation == 270) && pipelineLandscape != displayLandscape) {
                    metadataRotation
                } else 0
        }
        // Tiny output: the analysis encode is discarded.
        return Size(even(max(64, detW / 4)), even(max(64, detH / 4)))
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val full = smallFbo == 0 || analyzer.wantsFull()
        val w = if (full) detW else smallW
        val h = if (full) detH else smallH
        target.save()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if (full) fbo else smallFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        Gl.bindTexture(0, inputTexId)
        GLES20.glUniform1i(uniforms["uSrc"], 0)
        // 4 bilinear taps spread over the minification footprint (anti-aliasing).
        val spanX = max(0f, inW.toFloat() / w / 4f) / inW
        val spanY = max(0f, inH.toFloat() / h / 4f) / inH
        GLES20.glUniform2f(uniforms["uOffset"], spanX, spanY)
        quad!!.draw(program)

        val buf = (if (full) pixels else smallPixels)!!
        buf.rewind()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        Gl.check("detect readback")
        target.restore()

        analyzer.onFrame(buf, w, h, presentationTimeUs, rotationToApply, full)
    }

    private fun releaseTarget() {
        Gl.deleteFbo(fbo)
        Gl.deleteTexture(tex)
        fbo = 0
        tex = 0
        if (smallFbo != 0) {
            Gl.deleteFbo(smallFbo)
            Gl.deleteTexture(smallTex)
        }
        smallFbo = 0
        smallTex = 0
        smallPixels = null
    }

    override fun release() {
        super.release()
        releaseTarget()
        quad?.release()
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }

    private fun even(v: Int) = if (v % 2 == 0) v else v + 1

    companion object {
        // Draws the frame vertically flipped so glReadPixels yields top-row-first (upright) rows.
        private const val DOWNSCALE_FLIP_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
uniform vec2 uOffset;
void main() {
    vec2 uv = vec2(vTex.x, 1.0 - vTex.y);
    vec4 c = texture2D(uSrc, uv + vec2(-uOffset.x, -uOffset.y))
           + texture2D(uSrc, uv + vec2( uOffset.x, -uOffset.y))
           + texture2D(uSrc, uv + vec2(-uOffset.x,  uOffset.y))
           + texture2D(uSrc, uv + vec2( uOffset.x,  uOffset.y));
    gl_FragColor = vec4(c.rgb * 0.25, 1.0);
}
"""
    }
}
