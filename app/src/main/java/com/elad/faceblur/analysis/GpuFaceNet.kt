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
 * [RetinaFaceDetector]). It starts in the GPU's half precision; two frames that disagree
 * ([strike]) or a failure, and it tries full precision (still far faster than the CPU) and is
 * checked again; the same once more turns it off for the rest of the pass ([why] says why).
 */
class GpuFaceNet private constructor(private val models: Map<Pair<Int, Int>, File>) : Closeable {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "face-gpu") }
    private val nets = HashMap<Pair<Int, Int>, Net>()
    @Volatile private var off = false

    /** Full precision on the GPU (after half precision disagreed with OpenCV or failed). */
    @Volatile var precise = false
        private set

    /** Why it was turned off (for the stats line), or null while it's on. */
    @Volatile var why: String? = null
        private set

    // Frames with faces where the GPU and OpenCV agreed, and where they didn't.
    private val agreed = java.util.concurrent.atomic.AtomicInteger()
    private val strikes = java.util.concurrent.atomic.AtomicInteger()

    /** Enough frames agreed with OpenCV to use the GPU's results alone. */
    val trusted get() = agreed.get() >= CHECK_FRAMES

    /** A frame with faces where the GPU agreed with OpenCV. */
    fun vouch() {
        agreed.incrementAndGet()
    }

    private class Net(val interpreter: Interpreter, val delegate: GpuDelegate, val loc: Int, val conf: Int, val n: Int) {
        // The outputs' buffers, kept from frame to frame (only the GPU thread uses them).
        val outs = HashMap<Int, Any>()
        val locBuf: ByteBuffer = ByteBuffer.allocateDirect(n * 4 * 4).order(ByteOrder.nativeOrder())
        val confBuf: ByteBuffer = ByteBuffer.allocateDirect(n * 2 * 4).order(ByteOrder.nativeOrder())
        init {
            outs[loc] = locBuf
            outs[conf] = confBuf
            // Landmarks aren't used: they go to a scratch buffer.
            for (i in 0 until interpreter.outputTensorCount) {
                if (i != loc && i != conf) outs[i] = ByteBuffer.allocateDirect(interpreter.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
            }
        }
    }

    // Each detection worker's input buffer: the frame is converted straight into it (natively)
    // and handed to the GPU as is.
    private val inputs = ThreadLocal<ByteBuffer>()

    /**
     * The model's time on the GPU per frame (a running average; 0 before the first). A weak
     * GPU (a budget tablet's) can be several times slower than the CPU cores together.
     */
    @Volatile var inferMs = 0f
        private set

    fun supports(w: Int, h: Int) = !off && (w to h) in models

    /**
     * The model on [bgr] (already [w] x [h], 8-bit BGR): anchor offsets (n x 4) and scores
     * (n x 2), as from the ONNX model. Null if the GPU can't run it (then it's switched off).
     */
    fun run(bgr: Mat, w: Int, h: Int): Pair<FloatArray, FloatArray>? {
        if (!supports(w, h)) return null
        // Mean-subtracted floats, as the ONNX model's blob (the rows are already NHWC), written
        // by OpenCV straight into the buffer the GPU reads.
        val size = w * h * 3 * 4
        val input = inputs.get()?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { inputs.set(it) }
        input.rewind()
        val f = Mat(h, w, CvType.CV_32FC3, input)
        bgr.convertTo(f, CvType.CV_32FC3)
        Core.subtract(f, Scalar(104.0, 117.0, 123.0), f)
        f.release()
        return try {
            thread.submit(Callable { infer(w, h, input) }).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            Log.w(TAG, "GPU face detection failed${if (precise) "" else " in half precision"}: $cause")
            if (!precise) goPrecise() else disable("failed: ${cause.javaClass.simpleName}")
            null
        }
    }

    private fun infer(w: Int, h: Int, input: ByteBuffer): Pair<FloatArray, FloatArray> {
        val net = nets.getOrPut(w to h) { open(models.getValue(w to h)) }
        input.rewind()
        net.outs.values.forEach { (it as ByteBuffer).rewind() }
        val t0 = android.os.SystemClock.elapsedRealtime()
        net.interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), net.outs)
        val ms = (android.os.SystemClock.elapsedRealtime() - t0).toFloat()
        inferMs = if (inferMs == 0f) ms else inferMs * 0.8f + ms * 0.2f
        val l = FloatArray(net.n * 4)
        val c = FloatArray(net.n * 2)
        net.locBuf.rewind(); net.locBuf.asFloatBuffer().get(l)
        net.confBuf.rewind(); net.confBuf.asFloatBuffer().get(c)
        return l to c
    }

    private fun open(file: File): Net {
        val buffer = FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }
        val options = CompatibilityList().bestOptionsForThisDevice
        if (precise) options.setPrecisionLossAllowed(false)
        val delegate = GpuDelegate(options)
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
        return Net(interpreter, delegate, loc, conf, n)
    }

    /** Stops using the GPU for the rest of this pass. */
    fun disable(reason: String) {
        if (!off) why = reason
        off = true
    }

    /**
     * A frame where the GPU's faces differed from OpenCV's. One can be a borderline face that
     * fp16 put just over or under a score; a second means half precision isn't to be trusted
     * here (full precision is tried), and two more in full precision the GPU itself.
     */
    fun strike() {
        if (strikes.incrementAndGet() < MAX_STRIKES) return
        if (!precise) goPrecise() else disable("differed from the CPU")
    }

    /** Half precision didn't do: the models open again in full precision and are checked again. */
    private fun goPrecise() {
        if (precise || off) return
        precise = true
        strikes.set(0)
        agreed.set(0)
        Log.i(TAG, "GPU face detection: full precision from now on")
        thread.execute {
            nets.values.forEach { it.interpreter.close(); it.delegate.close() }
            nets.clear()
        }
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

        /** Frames that may disagree with OpenCV before the GPU is turned off. */
        const val MAX_STRIKES = 2

        /** Fine-pass sizes with a GPU model, and the file each one is in (see ModelManager). */
        val SIZES = listOf(1280 to 736, 736 to 1280)

        fun file(context: Context, w: Int, h: Int) = File(File(context.filesDir, "models"), "retinaface-mv2-${w}x$h.tflite")

        /** Why the last [create] gave no GPU detector (for the stats line). */
        @Volatile var unavailable = ""
            private set

        /**
         * The GPU detector if this phone can run it and the models are downloaded, else null.
         */
        fun create(context: Context): GpuFaceNet? {
            val files = SIZES.associateWith { (w, h) -> file(context, w, h) }.filterValues { it.length() > 1_000_000L }
            if (files.isEmpty()) {
                unavailable = "no GPU models yet"
                return null
            }
            // Emulators' GPUs can't run it reliably (and can't check it): CPU there.
            if (android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")) {
                unavailable = "emulator"
                return null
            }
            return try {
                if (!CompatibilityList().isDelegateSupportedOnThisDevice) {
                    unavailable = "GPU not supported"
                    null
                } else GpuFaceNet(files)
            } catch (t: Throwable) {
                Log.w(TAG, "no GPU delegate: $t")
                unavailable = "no GPU delegate: ${t.javaClass.simpleName}"
                null
            }
        }
    }
}
