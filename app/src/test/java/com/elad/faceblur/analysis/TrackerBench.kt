package com.elad.faceblur.analysis

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.TreeMap

/**
 * Times the tracker and the fusion on shared/vectors/tracker/bench.json (ci/make_bench_vectors.py),
 * the input web/tests/bench.cjs times too (docs/shared-code.md, 4.2). Only with ONIT_BENCH=1:
 *
 *     ONIT_BENCH=1 ./gradlew :app:testDebugUnitTest --tests com.elad.faceblur.analysis.TrackerBench
 *
 * Writes app/build/bench/tracker.json (median ms per second of video, and what was computed, so two
 * runs can be checked to compute the same) and prints it. Run it on the base and on the head, on one
 * machine, and compare (ci/perf_check.py reads the file).
 */
class TrackerBench {
    @Test
    fun bench() {
        assumeTrue("set ONIT_BENCH=1 to run the tracker bench", System.getenv("ONIT_BENCH") == "1")
        val v = JSONObject(input().readText())
        val fps = v.getInt("fps").toFloat()
        val n = v.getInt("n")
        val aspect = v.getDouble("aspect").toFloat()
        val cutsJson = v.getJSONArray("cuts")
        val cuts = (0 until cutsJson.length()).map { cutsJson.getInt(it) }
        val dets = HashMap<Int, List<Det>>()
        val yn = HashMap<Int, List<Det>>()
        val dj = v.getJSONObject("dets")
        for (k in dj.keys()) {
            val list = dj.getJSONArray(k)
            val frame = (0 until list.length()).map { i ->
                val d = list.getJSONArray(i)
                Det(Box(d.getDouble(0).toFloat(), d.getDouble(1).toFloat(), d.getDouble(2).toFloat(), d.getDouble(3).toFloat()), d.getDouble(4).toFloat(), d.getDouble(5).toFloat())
            }
            dets[k.toInt()] = frame
            // YuNet's view of the same faces, as bench.cjs: every other one, a little off, a little less sure.
            yn[k.toInt()] = frame.filterIndexed { i, _ -> i % 2 == 0 }.map { Det(Box(it.box.cx + 0.002f, it.box.cy - 0.001f, it.box.w * 1.05f, it.box.h * 1.05f), it.score * 0.9f) }
        }
        val motion = FloatArray(2 * n)
        val seconds = n / fps

        fun sum(tracks: List<TreeMap<Int, Box>>): String {
            var s = 0.0
            var f = 0
            for (t in tracks) for (b in t.values) { s += b.cx + b.cy + b.w + b.h; f++ }
            return "{\"tracks\":${tracks.size},\"frames\":$f,\"sum\":${Math.round(s * 1e3) / 1e3}}"
        }
        val parts = linkedMapOf<String, () -> String>(
            "track_s0" to { sum(OfflineTracker(fps, aspect).run(n, dets, motion, cuts)) },
            "track_s50" to {
                val base = OfflineTracker(fps, aspect).run(n, dets, motion, cuts)
                sum(DetectTuning.widen(base, 0.5f) { lv -> OfflineTracker(fps, aspect, false, DetectTuning.thresholds(lv)).run(n, dets, motion, cuts) })
            },
            // FUSE_PASSES passes over the video, as bench.cjs: one is too short to time steadily.
            "fuse" to {
                var c = 0
                var s = 0.0
                repeat(FUSE_PASSES) { for ((k, rf) in dets) for (d in DetectTuning.fuse(rf, yn.getValue(k))) { c++; s += d.score } }
                "{\"dets\":${c / FUSE_PASSES},\"sum\":${Math.round(s / FUSE_PASSES * 1e3) / 1e3}}"
            },
        )
        val iters = System.getenv("ONIT_BENCH_ITERS")?.toIntOrNull() ?: 25
        val ms = LinkedHashMap<String, Double>()
        val out = LinkedHashMap<String, String>()
        for ((name, fn) in parts) {
            repeat(10) { out[name] = fn() }
            val t = DoubleArray(iters) {
                val a = System.nanoTime()
                fn()
                (System.nanoTime() - a) / 1e6
            }
            t.sort()
            ms[name] = Math.round(t[iters / 2] / seconds / (if (name == "fuse") FUSE_PASSES else 1) * 1e4) / 1e4
        }
        val json = "{\"ms\":{" + ms.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "},\"out\":{" +
            out.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "},\"iters\":$iters,\"units\":\"ms per second of video\",\"java\":\"${System.getProperty("java.version")}\"}"
        val file = File("build/bench/tracker.json")
        file.parentFile?.mkdirs()
        file.writeText(json + "\n")
        println("TRACKER_BENCH $json")
    }

    private companion object {
        const val FUSE_PASSES = 20
    }

    /** shared/vectors/tracker/bench.json, from the module's directory (Gradle's) or the repository's. */
    private fun input(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "shared/vectors/tracker/bench.json")
            if (f.isFile) return f
            d = d.parentFile
        }
        error("shared/vectors/tracker/bench.json not found above ${System.getProperty("user.dir")}")
    }
}
