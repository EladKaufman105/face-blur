package com.elad.faceblur.analysis

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Selfie mode's detector: MediaPipe's BlazeFace full-range (see [BlazeFace]) on the LiteRT
 * interpreter, on the CPU (XNNPACK): at 192 x 192 it takes a few milliseconds, which a GPU
 * delegate (and the agreement check the face models' GPU pass needs) would not make worth it.
 * One instance per detection thread: an interpreter is not thread safe.
 */
class BlazeFaceDetector(modelFile: File) : Closeable {
    private val interpreter: Interpreter
    private val regOut: Int
    private val scoreOut: Int
    private val input = ByteBuffer.allocateDirect(BlazeFace.SIZE * BlazeFace.SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val regBuf = ByteBuffer.allocateDirect(BlazeFace.ANCHORS * BlazeFace.VALUES * 4).order(ByteOrder.nativeOrder())
    private val scoreBuf = ByteBuffer.allocateDirect(BlazeFace.ANCHORS * 4).order(ByteOrder.nativeOrder())
    private val regressors = FloatArray(BlazeFace.ANCHORS * BlazeFace.VALUES)
    private val logits = FloatArray(BlazeFace.ANCHORS)
    private val pixels = FloatArray(BlazeFace.SIZE * BlazeFace.SIZE * 3)
    private val boxed = Mat()
    private val rgb = Mat()
    private val floats = Mat()
    private val m = Mat(2, 3, CvType.CV_64F)
    private val small = Mat()

    /** The last [detect] call's time, in microseconds (read by the same thread right after it). */
    var lastUs = 0L
        private set

    init {
        val buffer = FileInputStream(modelFile).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }
        // One thread each: the analyzer's detection workers already keep the cores busy.
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(1))
        check(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 2) { "unexpected BlazeFace model" }
        val shape = interpreter.getInputTensor(0).shape()
        check(shape.contentEquals(intArrayOf(1, BlazeFace.SIZE, BlazeFace.SIZE, 3))) { "unexpected BlazeFace input ${shape.toList()}" }
        var reg = -1
        var score = -1
        for (i in 0 until 2) {
            val s = interpreter.getOutputTensor(i).shape()
            check(s.size == 3 && s[1] == BlazeFace.ANCHORS) { "unexpected BlazeFace output ${s.toList()}" }
            if (s[2] == BlazeFace.VALUES) reg = i else if (s[2] == 1) score = i
        }
        check(reg >= 0 && score >= 0) { "unexpected BlazeFace outputs" }
        regOut = reg
        scoreOut = score
    }

    /**
     * The faces in [frame] (8-bit BGR, the whole frame): the default ones, and with [withExtras]
     * (a sensitivity above 0) the extra ones from the lower pool too.
     */
    fun detect(frame: Mat, withExtras: Boolean): BlazeFace.Result {
        val t0 = System.nanoTime()
        // A frame much bigger than twice the model's side is first averaged down to that (INTER_AREA), so the
        // bilinear warp below doesn't skip over pixels (aliasing: small faces would suffer, and a plain warp
        // of a 1280 px frame to 192 px reads one pixel in six); the web's canvas smooths the same way.
        val long = maxOf(frame.cols(), frame.rows())
        val bgr = if (long > 2 * BlazeFace.SIZE) {
            val k = 2.0 * BlazeFace.SIZE / long
            Imgproc.resize(frame, small, Size(Math.max(1.0, Math.round(frame.cols() * k).toDouble()), Math.max(1.0, Math.round(frame.rows() * k).toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
            small
        } else frame
        val w = bgr.cols()
        val h = bgr.rows()
        // The frame letterboxed into the model's square the way MediaPipe does it (an affine
        // warp, bilinear, black bars), then RGB as (v - 127.5) / 127.5.
        val lb = BlazeFace.Letterbox(w, h)
        val s = lb.scale.toDouble()
        m.put(0, 0, s, 0.0, lb.padX * BlazeFace.SIZE.toDouble(), 0.0, s, lb.padY * BlazeFace.SIZE.toDouble())
        Imgproc.warpAffine(
            bgr, boxed, m, Size(BlazeFace.SIZE.toDouble(), BlazeFace.SIZE.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0),
        )
        Imgproc.cvtColor(boxed, rgb, Imgproc.COLOR_BGR2RGB)
        rgb.convertTo(floats, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
        floats.get(0, 0, pixels)
        input.rewind()
        input.asFloatBuffer().put(pixels)
        regBuf.rewind()
        scoreBuf.rewind()
        val outs = HashMap<Int, Any>(2)
        outs[regOut] = regBuf
        outs[scoreOut] = scoreBuf
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outs)
        regBuf.rewind()
        scoreBuf.rewind()
        regBuf.asFloatBuffer().get(regressors)
        scoreBuf.asFloatBuffer().get(logits)
        val result = BlazeFace.faces(regressors, logits, w, h, withExtras)
        lastUs = (System.nanoTime() - t0) / 1000
        return result
    }

    override fun close() {
        interpreter.close()
        boxed.release()
        small.release()
        rgb.release()
        floats.release()
        m.release()
    }

    companion object {
        /** The model's file in the selfie pack. */
        const val MODEL = "blaze_face_full_range.tflite"
    }
}
