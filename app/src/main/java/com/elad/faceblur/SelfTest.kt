package com.elad.faceblur

import android.content.Context
import android.net.Uri
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.audio.SfxLibrary
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.edit.NewCue
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.edit.TimedWord
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.subs.AudioDecoder
import com.elad.faceblur.subs.SpeakerDetector
import com.elad.faceblur.subs.SubPresets
import com.elad.faceblur.subs.WhisperNative
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File

/**
 * A check of the shipped (minified) app on a real device or emulator: every pipeline that goes
 * through native code or libraries, run once on a video from the app's own "selftest" folder
 * (CI copies it there with adb). Writes report.txt next to it, ending in PASS or FAIL.
 */
object SelfTest {
    fun dir(context: Context) = File(context.getExternalFilesDir(null), "selftest")

    suspend fun run(context: Context, videoName: String) {
        val d = dir(context)
        val report = File(d, "report.txt")
        val log = StringBuilder()
        fun line(s: String) {
            log.appendLine(s)
            report.writeText(log.toString())
        }
        report.delete()
        try {
            val video = File(d, videoName)
            require(video.canonicalPath.startsWith(d.canonicalPath) && video.exists()) { "no video" }
            val uri = Uri.fromFile(video)
            val info = VideoInfo.probe(context, uri)
            line("video ${info.width}x${info.height} ${info.durationMs}ms")
            val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
            val p = FaceBlurProcessor(context)
            val analysis = p.analyze(info, settings) {}
            val dets = analysis.dets.values.sumOf { it.size }
            line("analysis: ${analysis.frameCount} frames, $dets faces, ${analysis.tracks.size} tracks")
            require(dets > 0) { "no faces found" }
            val session = EditSession(info, settings, analysis, 0)
            val m = session.model
            val words = (0 until 3).map { TimedWord(500L + it * 400, 850L + it * 400) }
            m.setSentences(listOf(NewCue(500, 1800, "", "one two three", "en", words)))
            m.toggleBleep(m.cues.first().id, 1)
            val ding = SfxLibrary.load(context, "builtin:ding")
            line("sound effect: ${ding?.size ?: 0} samples")
            require((ding?.size ?: 0) > 1000) { "sound effect not decoded" }
            m.addSfx("builtin:ding", 1000, 1600)
            val masks = p.segment(info, analysis, File(context.cacheDir, "selftest_masks.bin")) {}
            line("people: ${masks.filled}/${masks.frameCount} masks")
            val export = ExportOptions(subs = SubLang.ORIGINAL, saveSrt = false, aspect = ExportAspect.VERTICAL, watermark = Watermark(text = "@selftest"))
            val style = SubPresets.all.first { it.name == "טיקטוק" }.style
            val out = p.render(info, settings, analysis, m, 0, export, style, AudioEdit(VoiceFx.ROBOT), Background(fill = BgFill.BLUR), masks) {}
            line("render: ${out.uri}")
            masks.close()
            val pcm = AudioDecoder.decode(context, uri, { true }) {}
            line("audio: ${pcm?.size ?: 0} samples")
            // Speech recognition and speakers, if CI copied their models in.
            val whisper = File(d, "ggml-tiny.bin")
            if (whisper.exists() && pcm != null) {
                val h = WhisperNative.init(whisper.path)
                require(h != 0L) { "whisper didn't load" }
                var last = -1
                val r = WhisperNative.transcribe(h, pcm, "en", 2, 1, null) { last = it }
                val n = WhisperNative.segments(h)
                val text = (0 until n).joinToString(" ") { String(WhisperNative.segmentText(h, it), Charsets.UTF_8) }
                WhisperNative.free(h)
                line("whisper: result $r, $n segments, progress $last, \"${text.take(60)}\"")
                require(r == 0) { "whisper failed" }
            }
            val speaker = File(d, "speaker.onnx")
            if (speaker.exists()) {
                val labels = SpeakerDetector.detect(context, uri, speaker.path, listOf(SpeakerDetector.Span(0, 3000), SpeakerDetector.Span(3000, 6000)), null) {}
                line("speakers: ${labels.toList()}")
            }
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.HEBREW).setTargetLanguage(TranslateLanguage.ENGLISH).build(),
            ).close()
            line("translator: ok")
            session.close()
            line("PASS")
        } catch (e: Throwable) {
            line("FAIL: $e")
            line(e.stackTraceToString().take(4000))
        }
    }
}
