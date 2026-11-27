package com.elad.faceblur.models

import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.subs.ModelManager
import org.junit.Assert.fail
import java.io.File
import java.io.FileNotFoundException

/**
 * The model packs for the instrumented tests: copied from the test assets (assets/models, where
 * ci/fetch-models.sh puts them, at the registry's pinned commits) and installed through the app's
 * own store, with the same size and SHA-256 checks as a download. No network. Also marks the
 * first-launch sheet as seen, so it doesn't cover the screens the tests look at.
 */
object ModelSeed {
    val ALL = listOf(PackId.FACES, PackId.PEOPLE, PackId.SPEAKERS, PackId.SELFIE)

    fun install(packs: List<PackId> = ALL) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val store = ModelStore.get(ctx)
        val tmp = File(ctx.cacheDir, "model-seed").apply { mkdirs() }
        try {
            for (id in packs) {
                if (store.status(id).installed == Installed.READY) continue
                val ok = store.install(id) { name ->
                    // The speaker model is also in the test assets under its old name (ci/fetch-test-media.sh).
                    val names = if (name == ModelManager.SPEAKER_FILE) listOf(name, "speaker.onnx") else listOf(name)
                    names.firstNotNullOfOrNull { n ->
                        try {
                            File(tmp, name).also { f -> inst.context.assets.open("models/$n").use { i -> f.outputStream().use { i.copyTo(it) } } }
                        } catch (e: FileNotFoundException) {
                            null
                        }
                    }
                }
                if (!ok) fail("model pack ${id.key} isn't in the test assets, or isn't the registry's (run ci/fetch-models.sh)")
            }
        } finally {
            tmp.deleteRecursively()
        }
        inst.runOnMainSync { ModelDownloads.get(ctx).prefs.markIntroSeen() }
    }
}
