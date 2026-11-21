package com.elad.faceblur.effects

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.SystemClock
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.elad.faceblur.gl.Gl
import com.elad.faceblur.gl.Quad
import com.elad.faceblur.gl.Uniforms
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lets the export be watched while it's made: passes every frame through unchanged and, every
 * [intervalMs], hands a small copy of it ([longSide] px) to [onFrame]. Goes last in the chain,
 * so the copy is the finished picture.
 */
class PreviewEffect(
    private val longSide: Int = 480,
    private val intervalMs: Long = 200,
    private val onFrame: (Bitmap) -> Unit,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        PreviewProgram(longSide, intervalMs, onFrame, useHdr)
}

private class PreviewProgram(
    private val longSide: Int,
    private val intervalMs: Long,
    private val onFrame: (Bitmap) -> Unit,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private var copy = 0
    private var shrink = 0
    private lateinit var copyUniforms: Uniforms
    private lateinit var shrinkUniforms: Uniforms
    private var quad: Quad? = null

    private var inW = 0
    private var inH = 0
    private var smallW = 0
    private var smallH = 0
    private var tex = 0
    private var fbo = 0
    private var pixels: ByteBuffer? = null
    private var last = 0L
    private val target = Gl.SavedTarget()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (copy == 0) {
            copy = Gl.program(COPY_FRAGMENT)
            shrink = Gl.program(SHRINK_FLIP_FRAGMENT)
            copyUniforms = Uniforms(copy)
            shrinkUniforms = Uniforms(shrink)
            quad = Quad()
        }
        if (inputWidth != inW || inputHeight != inH) {
            releaseTarget()
            inW = inputWidth
            inH = inputHeight
            val s = minOf(1f, longSide.toFloat() / max(inputWidth, inputHeight))
            smallW = max(2, (inputWidth * s).roundToInt())
            smallH = max(2, (inputHeight * s).roundToInt())
            tex = Gl.createTexture(smallW, smallH, highPrecision = false)
            fbo = Gl.createFbo(tex)
            pixels = ByteBuffer.allocateDirect(smallW * smallH * 4).order(ByteOrder.nativeOrder())
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES20.glDisable(GLES20.GL_BLEND)
        val now = SystemClock.elapsedRealtime()
        if (now - last >= intervalMs) {
            last = now
            target.save()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, smallW, smallH)
            GLES20.glUseProgram(shrink)
            Gl.bindTexture(0, inputTexId)
            GLES20.glUniform1i(shrinkUniforms["uSrc"], 0)
            val spanX = max(0f, inW.toFloat() / smallW / 4f) / inW
            val spanY = max(0f, inH.toFloat() / smallH / 4f) / inH
            GLES20.glUniform2f(shrinkUniforms["uOffset"], spanX, spanY)
            quad!!.draw(shrink)
            val buf = pixels!!
            buf.rewind()
            GLES20.glReadPixels(0, 0, smallW, smallH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            Gl.check("preview readback")
            target.restore()
            buf.rewind()
            val bmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            onFrame(bmp)
        }
        // The frame itself, unchanged.
        GLES20.glUseProgram(copy)
        Gl.bindTexture(0, inputTexId)
        GLES20.glUniform1i(copyUniforms["uSrc"], 0)
        quad!!.draw(copy)
        Gl.check("preview pass-through")
    }

    private fun releaseTarget() {
        Gl.deleteFbo(fbo)
        Gl.deleteTexture(tex)
        fbo = 0
        tex = 0
    }

    override fun release() {
        super.release()
        releaseTarget()
        quad?.release()
        if (copy != 0) GLES20.glDeleteProgram(copy)
        if (shrink != 0) GLES20.glDeleteProgram(shrink)
        copy = 0
        shrink = 0
    }

    companion object {
        private const val COPY_FRAGMENT = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
void main() {
    gl_FragColor = texture2D(uSrc, vTex);
}
"""

        // Vertically flipped so glReadPixels yields top-row-first rows; 4 taps against aliasing.
        private const val SHRINK_FLIP_FRAGMENT = """
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
