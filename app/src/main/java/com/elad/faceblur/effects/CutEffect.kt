package com.elad.faceblur.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.elad.faceblur.edit.CutMap
import com.elad.faceblur.gl.Gl
import com.elad.faceblur.gl.Quad
import com.elad.faceblur.gl.Uniforms

/**
 * Takes the cut stretches out of the export: their frames are dropped and the frames after them
 * move earlier, so the video plays on without a gap (the sound is cut the same way, see
 * CutAudioProcessor). Goes after every effect that works by the source's time.
 */
class CutEffect(private val cuts: CutMap) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = CutProgram(cuts, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = cuts.isEmpty
}

private class CutProgram(private val cuts: CutMap, useHdr: Boolean) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
    private var copy = 0
    private lateinit var uniforms: Uniforms
    private var quad: Quad? = null
    private var listener: GlShaderProgram.InputListener? = null

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        listener = inputListener
        super.setInputListener(inputListener)
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        val out = cuts.outUs(presentationTimeUs)
        if (out < 0) {
            // Dropped: hand the frame back and ask for the next one.
            listener?.onInputFrameProcessed(inputTexture)
            listener?.onReadyToAcceptInputFrame()
            return
        }
        super.queueInputFrame(glObjectsProvider, inputTexture, out)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (copy == 0) {
            copy = Gl.program(COPY)
            uniforms = Uniforms(copy)
            quad = Quad()
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(copy)
        Gl.bindTexture(0, inputTexId)
        GLES20.glUniform1i(uniforms["uSrc"], 0)
        quad!!.draw(copy)
        Gl.check("cut pass-through")
    }

    override fun release() {
        super.release()
        quad?.release()
        if (copy != 0) GLES20.glDeleteProgram(copy)
        copy = 0
    }

    companion object {
        private const val COPY = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uSrc;
void main() {
    gl_FragColor = texture2D(uSrc, vTex);
}
"""
    }
}
