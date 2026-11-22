package com.elad.faceblur.analysis

import android.content.Context
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * RetinaFace's big pass on the phone's GPU (LiteRT GPU delegate). The models are static, one per
 * frame shape the fine pass uses for 16:9 and 9:16 video (1280x736, 736x1280); other sizes stay
 * on the CPU (OpenCV). Everything GPU runs on one thread of its own (the delegate is tied to the
 * thread that made it); the detection workers wait their turn.
 *
 * Before trusting it, the first frames with faces are also run through OpenCV and compared (see
 * [RetinaFaceDetector]); [disable] turns it off for the rest of the pass.
 */
class GpuFaceNet private constructor(private val models: Map<Pair<Int, Int>, File>) : Closeable {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "face-gpu") }
    private val nets = HashMap<Pair<Int, Int>, Net>()
    @Volatile private var off = false

    // Frames with faces where the GPU and OpenCV agreed.
    private val agreed = java.util.concurrent.atomic.AtomicInteger()

    /** Enough frames agreed with OpenCV to use the GPU's results alone. */
    val trusted get() = agreed.get() >= CHECK_FRAMES

    /** A frame with faces where the GPU agreed with OpenCV. */
    fun vouch() {
        agreed.incrementAndGet()
    }

    private class Net(val interpreter: Interpreter, val delegate: GpuDelegate, val input: ByteBuffer, val loc: Int, val conf: Int, val n: Int)

    fun supports(w: Int, h: Int) = !off && (w to h) in models

    /**
     * The model on [bgr] (already [w] x [h], 8-bit BGR): anchor offsets (n x 4) and scores
     * (n x 2), as from the ONNX model. Null if the GPU can't run it (then it's switched off).
     */
    fun run(bgr: Mat, w: Int, h: Int): Pair<FloatArray, FloatArray>? {
        if (!supports(w, h)) return null
        // Mean-subtracted floats, as the ONNX model's blob; the rows are already NHWC.
        val f = Mat()
        bgr.convertTo(f, CvType.CV_32FC3)
        Core.subtract(f, Scalar(104.0, 117.0, 123.0), f)
        val pixels = FloatArray(w * h * 3)
        f.get(0, 0, pixels)
        f.release()
        return try {
            thread.submit(Callable { infer(w, h, pixels) }).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "GPU face detection failed, using the CPU: ${e.cause ?: e}")
            disable()
            null
        }
    }

    private fun infer(w: Int, h: Int, pixels: FloatArray): Pair<FloatArray, FloatArray> {
        val net = nets.getOrPut(w to h) { open(models.getValue(w to h)) }
        net.input.rewind()
        net.input.asFloatBuffer().put(pixels)
        val loc = ByteBuffer.allocateDirect(net.n * 4 * 4).order(ByteOrder.nativeOrder())
        val conf = ByteBuffer.allocateDirect(net.n * 2 * 4).order(ByteOrder.nativeOrder())
        val outs = HashMap<Int, Any>()
        outs[net.loc] = loc
        outs[net.conf] = conf
        // Landmarks aren't used: let them go to a scratch buffer.
        for (i in 0 until net.interpreter.outputTensorCount) {
            if (i != net.loc && i != net.conf) outs[i] = ByteBuffer.allocateDirect(net.interpreter.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
        }
        net.interpreter.runForMultipleInputsOutputs(arrayOf<Any>(net.input), outs)
        val l = FloatArray(net.n * 4)
        val c = FloatArray(net.n * 2)
        loc.rewind(); loc.asFloatBuffer().get(l)
        conf.rewind(); conf.asFloatBuffer().get(c)
        return l to c
    }

    private fun open(file: File): Net {
        val buffer = FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }
        val delegate = GpuDelegate(CompatibilityList().bestOptionsForThisDevice)
        val interpreter = Interpreter(buffer, Interpreter.Options().addDelegate(delegate))
        var loc = -1
        var conf = -1
        var n = 0
        for (i in 0 until interpreter.outputTensorCount) {
            val s = interpreter.getOutputTensor(i).shape()
            when (s.last()) {
                4 -> { loc = i; n = s[1] }
                2 -> conf = i
            }
        }
        check(loc >= 0 && conf >= 0) { "unexpected outputs" }
        val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
        return Net(interpreter, delegate, input, loc, conf, n)
    }

    /** Stops using the GPU for the rest of this pass. */
    fun disable() {
        off = true
    }

    val active get() = !off

    override fun close() {
        thread.execute {
            nets.values.forEach { it.interpreter.close(); it.delegate.close() }
            nets.clear()
        }
        thread.shutdown()
        thread.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        private const val TAG = "ONIT"

        /** Frames with faces checked against OpenCV before the GPU's results are used alone. */
        const val CHECK_FRAMES = 3

        /** Fine-pass sizes with a GPU model, and the file each one is in (see ModelManager). */
        val SIZES = listOf(1280 to 736, 736 to 1280)

        fun file(context: Context, w: Int, h: Int) = File(File(context.filesDir, "models"), "retinaface-mv2-${w}x$h.tflite")

        /**
         * The GPU detector if this phone can run it and the models are downloaded, else null.
         */
        fun create(context: Context): GpuFaceNet? {
            val files = SIZES.associateWith { (w, h) -> file(context, w, h) }.filterValues { it.length() > 1_000_000L }
            if (files.isEmpty()) return null
            // Emulators' GPUs can't run it reliably (and can't check it): CPU there.
            if (android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")) return null
            return try {
                if (!CompatibilityList().isDelegateSupportedOnThisDevice) null else GpuFaceNet(files)
            } catch (t: Throwable) {
                Log.w(TAG, "no GPU delegate: $t")
                null
            }
        }
    }
}
