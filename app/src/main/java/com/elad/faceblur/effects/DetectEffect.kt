package com.elad.faceblur.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES20
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.elad.faceblur.detect.FaceDetectorEngine
import com.elad.faceblur.gl.Gl
import com.elad.faceblur.gl.Quad
import com.elad.faceblur.gl.Uniforms
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** Face boxes found in one analyzed frame: normalized, top-left origin, pipeline orientation. */
class FrameDetections(val timeUs: Long, val boxes: List<RectF>)

/** Thread-safe collector filled on the GL thread during the analysis pass. */
class DetectionSink {
    private val frames = ArrayList<FrameDetections>()

    @Volatile var frameWidth = 0
    @Volatile var frameHeight = 0

    @Synchronized fun add(frame: FrameDetections) {
        frames += frame
    }

    @Synchronized fun clear() {
        frames.clear()
    }

    @Synchronized fun snapshot(): List<FrameDetections> = frames.sortedBy { it.timeUs }
}

/**
 * Analysis pass effect: downsamples each frame, reads it back and runs face detection.
 * Its own video output is a small black frame (the analysis file is thrown away).
 */
class DetectEffect(
    private val engine: FaceDetectorEngine,
    private val sink: DetectionSink,
    private val intervalUs: Long,
    private val maxSide: Int,
    private val metadataRotation: Int,
    private val displayLandscape: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DetectProgram(engine, sink, intervalUs, maxSide, metadataRotation, displayLandscape, useHdr)
}

private class DetectProgram(
    private val engine: FaceDetectorEngine,
    private val sink: DetectionSink,
    private val intervalUs: Long,
    private val maxSide: Int,
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
    private var bitmap: Bitmap? = null
    private var rotationToApply = 0
    private var lastUs = Long.MIN_VALUE
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
            val scale = minOf(1f, maxSide.toFloat() / max(inputWidth, inputHeight))
            detW = max(2, (inputWidth * scale).roundToInt())
            detH = max(2, (inputHeight * scale).roundToInt())
            tex = Gl.createTexture(detW, detH, highPrecision = false)
            fbo = Gl.createFbo(tex)
            pixels = ByteBuffer.allocateDirect(detW * detH * 4).order(ByteOrder.nativeOrder())
            bitmap = Bitmap.createBitmap(detW, detH, Bitmap.Config.ARGB_8888)
            sink.frameWidth = detW
            sink.frameHeight = detH

            // Media3 normally hands effects display-oriented frames. If it didn't (frame
            // aspect disagrees with the display aspect), rotate before detection.
            val pipelineLandscape = inputWidth >= inputHeight
            rotationToApply =
                if ((metadataRotation == 90 || metadataRotation == 270) && pipelineLandscape != displayLandscape) {
                    metadataRotation
                } else 0
        }
        // Tiny output: the analysis encode is discarded.
        val outW = even(max(64, detW / 4))
        val outH = even(max(64, detH / 4))
        return Size(outW, outH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (lastUs != Long.MIN_VALUE && presentationTimeUs - lastUs < intervalUs) return
        lastUs = presentationTimeUs

        target.save()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, detW, detH)
        GLES20.glUseProgram(program)
        Gl.bindTexture(0, inputTexId)
        GLES20.glUniform1i(uniforms["uSrc"], 0)
        // 4 bilinear taps spaced to cover the minification footprint (anti-aliasing).
        val spanX = max(0f, inW.toFloat() / detW / 4f) / inW
        val spanY = max(0f, inH.toFloat() / detH / 4f) / inH
        GLES20.glUniform2f(uniforms["uOffset"], spanX, spanY)
        quad!!.draw(program)

        val buf = pixels!!
        buf.rewind()
        GLES20.glReadPixels(0, 0, detW, detH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        Gl.check("detect readback")
        target.restore()

        buf.rewind()
        val bmp = bitmap!!
        bmp.copyPixelsFromBuffer(buf)
        val boxes = engine.detect(bmp, rotationToApply)
        sink.add(FrameDetections(presentationTimeUs, boxes))
    }

    private fun releaseTarget() {
        Gl.deleteFbo(fbo)
        Gl.deleteTexture(tex)
        fbo = 0
        tex = 0
        bitmap?.recycle()
        bitmap = null
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
