package com.elad.faceblur

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.audio.Mp3Export
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.NewCue
import com.elad.faceblur.edit.Stabilizer
import com.elad.faceblur.edit.TimedWord
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.edit.EditorPanel
import com.elad.faceblur.ui.EditorScreen
import com.elad.faceblur.ui.EditorTheme
import com.elad.faceblur.ui.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * The test build's features: cuts (by hand, silences, words) in the player and the export,
 * voice cleanup, stabilization, no location in what's exported, and video to MP3. The pure math
 * (time map, silences, denoise, loudness, limiter) is a JVM test: audio/AudioAndCutMathTest.
 */
@RunWith(AndroidJUnit4::class)
class LabsTest {
    /** The model packs, from the test assets (ci/fetch-models.sh), installed as the app installs a download. */
    @org.junit.Before
    fun seedModels() = com.elad.faceblur.models.ModelSeed.install()


    @get:Rule
    val rule = createComposeRule()

    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val outDir get() = File(ctx.getExternalFilesDir(null), "labs").apply { mkdirs() }
    private val log = StringBuilder()

    private fun note(s: String) {
        log.appendLine(s)
        File(outDir, "log_${Thread.currentThread().stackTrace.firstOrNull { it.methodName in TESTS }?.methodName ?: "labs"}.txt").writeText(log.toString())
        android.util.Log.i("ONIT-Labs", s)
    }

    private fun sample(name: String): File {
        val f = File(ctx.cacheDir, "labs_$name")
        inst.context.assets.open("samples/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /**
     * Cuts and cleanup in the export, and no location in the result: a clip tagged with a place
     * comes out shorter by what was cut, with its sound in step, and without the place.
     */
    @Test
    fun cutsCleanupPrivacy() {
        val plain = sample("speech_two.mp4")
        val tagged = File(ctx.cacheDir, "labs_tagged.mp4")
        remuxWithLocation(plain, tagged, 31.7767f, 35.2345f)
        val inLoc = MediaMetadataRetriever().run { setDataSource(tagged.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).also { release() } }
        note("input location: $inLoc")
        check(inLoc != null) { "the test clip has no location" }
        val info = VideoInfo.probe(ctx, Uri.fromFile(tagged))
        val analysis = FaceBlurProcessor.framesOnly(ctx, info)
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val session = EditSession(info, settings, analysis, 0, subtitlesOnly = true)
        val m = session.model
        val inDur = durationsUs(tagged)
        // Two cuts by hand and one word: 3 s taken out in all.
        m.cut(3000, 5000)
        m.setSentences(listOf(NewCue(8000, 10_000, "", "one two three four", "en", listOf(TimedWord(8000, 8400), TimedWord(8500, 9500), TimedWord(9600, 9800), TimedWord(9850, 10_000)))))
        check(m.toggleCutWord(m.cues.first().id, 1)) { "cut word" }
        val removed = m.cutMap().removedUs
        note("removed ${removed / 1000} ms in ${m.removed.size} stretches; input $inDur")
        val audio = AudioEdit(denoise = 2, enhance = true, loud = true, gainDb = 4f)
        val saved = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, m, 0, ExportOptions(saveSrt = false), audio = audio) {}
        }
        val out = File(ctx.cacheDir, "labs_out.mp4")
        ctx.contentResolver.openInputStream(saved.uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        val outDur = durationsUs(out)
        val outLoc = MediaMetadataRetriever().run { setDataSource(out.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).also { release() } }
        note("output: $outDur, location $outLoc, boxes hidden ${FaceBlurProcessor.lastScrubbed}")
        if (outLoc != null) fail("location kept: $outLoc")
        val want = inDur.first - removed
        if (abs(outDur.first - want) > 250_000) fail("video ${outDur.first} us, expected ~$want")
        if (outDur.second > 0 && abs(outDur.second - outDur.first) > 300_000) fail("sound ${outDur.second} us vs video ${outDur.first} us")
        // The cut word left the subtitle too.
        val exported = m.exportCues(false).first()
        check(!exported.srcText.contains("two")) { "cut word still in subtitle: ${exported.srcText}" }
    }

    /**
     * Stabilization on a moving-camera clip: computed, shown in the player, exported. Then the same
     * clip opened without face detection: its motion is measured on demand (once stabilizeWithoutFaces).
     */
    @Test
    fun stabilizeAndPanels() {
        val src = sample("walk.mp4")
        val info = VideoInfo.probe(ctx, Uri.fromFile(src))
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val analysis = runBlocking(Dispatchers.Main) { FaceBlurProcessor(ctx).analyze(info, settings) {} }
        val s = Stabilizer.from(analysis.times, analysis.motion, analysis.cuts, analysis.aspect, analysis.fps, analysis.rotation)
        note("stabilizer: ${if (s == null) "none" else "zoom %.3f, max shift %.3f".format(s.zoom, s.shiftX.maxOf { abs(it) })}")
        val session = EditSession(info, settings, analysis, 0)
        val ctrl = EditorController(session, CoroutineScope(SupervisorJob() + Dispatchers.Main), ctx.applicationContext)
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EditorScreen(ctrl, onClose = {}, onExport = {})
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(2500)
        // The main controls can be found by their tags, whatever the language.
        for (tag in listOf(Tags.BACK, Tags.EXPORT, Tags.PLAY, Tags.UNDO, Tags.REDO, Tags.ZOOM_IN, Tags.ZOOM_OUT) + listOf("PRIVACY", "EDIT", "PICTURE", "SUBTITLES", "SOUND", "BRAND").map { Tags.group(it) }) {
            rule.onNodeWithTag(tag).assertExists()
        }
        // A group opens its first tool; its tabs switch between the group's tools.
        rule.onNodeWithTag(Tags.group("EDIT")).performClick()
        rule.waitForIdle()
        if (inst.onMain { ctrl.panel } != EditorPanel.CUT) fail("the edit group opened ${inst.onMain { ctrl.panel }}")
        rule.onNodeWithTag(Tags.tab(EditorPanel.STABILIZE)).performClick()
        rule.waitForIdle()
        if (inst.onMain { ctrl.panel } != EditorPanel.STABILIZE) fail("the tab opened ${inst.onMain { ctrl.panel }}")
        rule.runOnIdle { ctrl.chooseStabilize(2); ctrl.stabilizePanel = true }
        Thread.sleep(1500)
        shot("1_stabilized.png")
        note("stabilize on: ${inst.onMain { ctrl.stabilize }}")
        // The cuts panel, with a cut by hand and words.
        rule.runOnIdle {
            ctrl.model.setSentences(listOf(NewCue(300, 2600, "שלום לכולם אממ מה שלומכם היום", "", "he", null)))
            ctrl.model.cut(2800, 3600)
            ctrl.cutPanel = true
        }
        // Undo takes the cut away, redo puts it back.
        rule.runOnIdle {
            val n = ctrl.cutCount
            ctrl.undo()
            val undone = ctrl.cutCount
            ctrl.redo()
            note("cuts: $n, after undo $undone, after redo ${ctrl.cutCount}")
            if (undone != n - 1 || ctrl.cutCount != n) fail("undo/redo of a cut: $n -> $undone -> ${ctrl.cutCount}")
        }
        Thread.sleep(1200)
        shot("2_cut_panel.png")
        rule.runOnIdle { ctrl.cutFillers() }
        Thread.sleep(800)
        note("fillers cut: total cuts ${inst.onMain { ctrl.cutCount }}")
        shot("3_fillers_cut.png")
        rule.runOnIdle { ctrl.soundTab = 1; ctrl.voicePanel = true; ctrl.setDenoise(2); ctrl.setEnhance(true) }
        Thread.sleep(1200)
        shot("4_voice_cleanup.png")
        // Playback jumps over the cut.
        rule.runOnIdle { ctrl.voicePanel = false; ctrl.seek(0) }
        Thread.sleep(800)
        inst.runOnMainSync { ctrl.playToggler() }
        Thread.sleep(4500)
        inst.runOnMainSync { ctrl.pauser() }
        note("after playing: frame ${inst.onMain { ctrl.frame }}")
        // Export with the camera steadied.
        val saved = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, ctrl.model, 0, ExportOptions(saveSrt = false), stabilizer = s) {}
        }
        val out = File(ctx.cacheDir, "labs_steady.mp4")
        ctx.contentResolver.openInputStream(saved.uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        note("steadied export: ${durationsUs(out)}")
        frameAt(out, 1_000_000L)?.let { save(it, "5_steady_frame.png") }
        stabilizeWithoutFaces(info)
    }

    /** Steadying a video opened without face detection: its motion is measured on demand. */
    private fun stabilizeWithoutFaces(info: VideoInfo) {
        val analysis = FaceBlurProcessor.framesOnly(ctx, info)
        val session = EditSession(info, Settings(BlurMode.BLUR, 0.6f, 1.6f, false), analysis, 0, subtitlesOnly = true)
        val ctrl = EditorController(session, CoroutineScope(SupervisorJob() + Dispatchers.Main), ctx.applicationContext)
        for (level in listOf(1, 3)) {
            inst.runOnMainSync { ctrl.chooseStabilize(level) }
            val t0 = System.currentTimeMillis()
            while (inst.onMain { ctrl.stabilizeProgress != null || (ctrl.stabilizer == null && ctrl.stabilize) }) {
                if (System.currentTimeMillis() - t0 > 180_000) fail("motion pass timed out")
                Thread.sleep(200)
            }
            val zoom = inst.onMain { ctrl.stabilizeZoomPercent }
            note("level $level without faces: zoom $zoom% (motion measured in ${System.currentTimeMillis() - t0} ms)")
            if (inst.onMain { ctrl.stabilizer } == null) fail("no stabilizer at level $level")
        }
    }

    /** On a landscape tablet, the selected clip's properties in the side panel. Screenshots: weekly. */
    @Test
    @Weekly
    fun propertiesOnTablet() {
        val src = sample("classroom.mp4")
        val info = VideoInfo.probe(ctx, Uri.fromFile(src))
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val analysis = runBlocking(Dispatchers.Main) { FaceBlurProcessor(ctx).analyze(info, settings) {} }
        val ctrl = EditorController(EditSession(info, settings, analysis, 0), CoroutineScope(SupervisorJob() + Dispatchers.Main), ctx.applicationContext)
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
                        val base = androidx.compose.ui.platform.LocalConfiguration.current
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val scale = minOf(maxWidth.value / 1280f, maxHeight.value / 800f)
                        val conf = android.content.res.Configuration(base).apply {
                            screenWidthDp = 1280; screenHeightDp = 800; smallestScreenWidthDp = 800
                            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        }
                        CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalConfiguration provides conf,
                            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density * scale, density.fontScale),
                        ) {
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.testTag("tablet").size(1280.dp, 800.dp)) {
                                EditorScreen(ctrl, onClose = {}, onExport = {})
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(2500)
        val id = inst.onMain { ctrl.model.tracks.firstOrNull()?.id }
        if (id == null) fail("no faces found")
        rule.runOnIdle { ctrl.focus(id!!) }
        Thread.sleep(1000)
        tabletShot("6_tablet_blur_props.png")
        rule.runOnIdle {
            ctrl.model.setSentences(listOf(NewCue(500, 2500, "שלום לכולם", "", "he", null)))
            ctrl.selectCue(ctrl.model.cues.first().id)
        }
        Thread.sleep(1000)
        tabletShot("7_tablet_cue_props.png")
        rule.runOnIdle { ctrl.deselect() }
        Thread.sleep(800)
        tabletShot("8_tablet_back_to_tool.png")
        note("properties shots taken")
    }

    private fun tabletShot(name: String) {
        save(rule.onNodeWithTag("tablet").captureToImage().asAndroidBitmap(), name)
    }

    /** Video to MP3: decoded on the device, encoded with LAME, saved in Download/ONIT. */
    @Test
    fun mp3() {
        val src = sample("speech_two.mp4")
        val r = Mp3Export.convert(ctx, Uri.fromFile(src), "labs_test", 192, { true }) {}
        val f = File(ctx.cacheDir, "labs.mp3")
        ctx.contentResolver.openInputStream(r.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        val fmt = ex.getTrackFormat(0)
        val mime = fmt.getString(MediaFormat.KEY_MIME)
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
        ex.release()
        val srcDur = durationsUs(src).second
        note("mp3: ${r.name}, ${r.bytes} bytes, $mime, $dur us (source sound $srcDur us)")
        check(mime == "audio/mpeg") { "mime $mime" }
        check(r.bytes > 10_000) { "too small" }
        if (dur > 0 && srcDur > 0) check(abs(dur - srcDur) < 300_000) { "duration $dur vs $srcDur" }
    }

    // ------------------------------------------------------------------ helpers

    /** (video, audio) track durations in µs (0 if none). */
    private fun durationsUs(f: File): Pair<Long, Long> {
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        var v = 0L
        var a = 0L
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            // The last sample's time, read through (the header's duration may be rounded).
            ex.selectTrack(i)
            var last = 0L
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                last = maxOf(last, t)
                if (!ex.advance()) break
            }
            ex.unselectTrack(i)
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (mime.startsWith("video/")) v = last else if (mime.startsWith("audio/")) a = last
        }
        ex.release()
        return v to a
    }

    /** Copies [src] to [dst] as is, with a filming place written in. */
    private fun remuxWithLocation(src: File, dst: File, lat: Float, lon: Float) {
        val ex = MediaExtractor()
        ex.setDataSource(src.path)
        val mux = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux.setLocation(lat, lon)
        val map = HashMap<Int, Int>()
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) map[i] = mux.addTrack(fmt)
        }
        mux.start()
        val buf = ByteBuffer.allocate(4 shl 20)
        val info = MediaCodec.BufferInfo()
        for ((track, out) in map) {
            ex.selectTrack(track)
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(out, buf, info)
                if (!ex.advance()) break
            }
            ex.unselectTrack(track)
        }
        mux.stop()
        mux.release()
        ex.release()
    }

    private fun frameAt(f: File, us: Long): Bitmap? = runCatching {
        MediaMetadataRetriever().run {
            setDataSource(f.path)
            getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST).also { release() }
        }
    }.getOrNull()

    private fun save(b: Bitmap, name: String) {
        File(outDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String) {
        save(rule.onRoot().captureToImage().asAndroidBitmap(), name)
    }

    private fun <T> android.app.Instrumentation.onMain(block: () -> T): T {
        var r: Any? = null
        runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    companion object {
        private val TESTS = setOf("cutsCleanupPrivacy", "stabilizeAndPanels", "propertiesOnTablet", "mp3")
    }
}
