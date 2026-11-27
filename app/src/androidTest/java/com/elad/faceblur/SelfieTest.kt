package com.elad.faceblur

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.analysis.DetectMode
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.models.ModelSeed
import com.elad.faceblur.models.PackId
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Selfie detection (MediaPipe's BlazeFace on every frame, from the selfie pack) on a talking-head
 * clip: the analysis runs on the pack alone, finds a face on most frames and tracks it.
 */
@RunWith(AndroidJUnit4::class)
class SelfieTest {
    @Before
    fun seed() = ModelSeed.install(listOf(PackId.SELFIE))

    @Test
    fun findsTheFaceOnMostFrames() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val src = File(ctx.cacheDir, "headpose.mp4")
        inst.context.assets.open("samples/headpose.mp4").use { i -> src.outputStream().use { i.copyTo(it) } }
        val info = VideoInfo.probe(ctx, Uri.fromFile(src))
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false, selfie = true)
        assertEquals(DetectMode.SELFIE, settings.detect)
        runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).analyze(info, settings) {}
        }.use { a ->
            val frames = a.frameCount
            // Every frame is detected on (the clip has two faces close to the camera on all of them).
            val withFace = a.dets.values.count { d -> d.any { it.score >= 0.5f } }
            assertTrue("selfie analysis saw no frames", frames > 0)
            assertTrue("a face on only $withFace of $frames frames", withFace >= 0.9f * frames)
            assertTrue("no face track", a.tracks.isNotEmpty())
            // The tracks cover most frames too.
            val covered = (0 until frames).count { f -> a.tracks.any { it.containsKey(f) } }
            assertTrue("tracks cover only $covered of $frames frames", covered >= 0.9f * frames)
        }
    }
}
