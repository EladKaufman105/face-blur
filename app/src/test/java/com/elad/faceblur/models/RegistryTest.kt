package com.elad.faceblur.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The registry the app ships (assets/model-registry.json) and the rules its reader enforces. */
class RegistryTest {

    private fun shipped(): ModelRegistry {
        // Gradle runs unit tests in the module's folder; elsewhere, from the repository root.
        val f = listOf("src/main/assets/model-registry.json", "app/src/main/assets/model-registry.json")
            .map { File(it) }.firstOrNull { it.isFile } ?: error("model-registry.json not found from ${File(".").absolutePath}")
        return ModelRegistry.parse(f.readText())
    }

    @Test
    fun everyPackIsThere() {
        val r = shipped()
        for (id in PackId.entries) assertNotNull("pack ${id.key}", r.find(id))
    }

    @Test
    fun urlsArePinned() {
        for (p in shipped().packs.filter { !it.unpinned }) for (f in p.files) {
            assertTrue(f.url, f.url.startsWith("https://"))
            // A commit (40 hex) in a raw URL, or an immutable models-* release: never a branch.
            val raw = Regex("^https://raw\\.githubusercontent\\.com/EladKaufman105/face-blur/([0-9a-f]{40})/.+")
            val release = Regex("^https://github\\.com/EladKaufman105/face-blur/releases/download/models-[0-9]+/.+")
            // MediaPipe's own versioned model files (a /float16/1/ path: a new version is a new path). The path alone isn't the pin:
            // the file's SHA-256 (and size) in the registry is, and every download and every use is checked against it.
            val mediapipe = Regex("^https://storage\\.googleapis\\.com/mediapipe-models/[a-z_]+/[a-z_]+/float(16|32)/[0-9]+/[A-Za-z0-9_.]+")
            assertTrue("not pinned: ${f.url}", raw.matches(f.url) || release.matches(f.url) || mediapipe.matches(f.url))
        }
    }

    @Test
    fun readyPacksHaveSizesAndChecksums() {
        for (p in shipped().packs) {
            if (p.pending != null || p.unpinned) continue
            for (f in p.files) {
                assertTrue(f.name, f.size > 0)
                assertTrue(f.name, ModelRegistry.isSha256(f.sha256))
            }
        }
    }

    /** Face blur's pack is whole: both detectors and both GPU sizes (the privacy rule needs all four). */
    @Test
    fun facesPackIsComplete() {
        val faces = shipped()[PackId.FACES]
        assertNull(faces.pending)
        val names = faces.files.map { it.name }.toSet()
        assertEquals(
            setOf("retinaface_mv2.onnx", "face_detection_yunet_2023mar.onnx", "retinaface-mv2-1280x736.tflite", "retinaface-mv2-736x1280.tflite"),
            names,
        )
        assertTrue(faces.recommended)
        assertEquals(19_150_372L, faces.bytes)
    }

    @Test
    fun peopleAndSpeakers() {
        val r = shipped()
        assertEquals(3, r[PackId.PEOPLE].files.size)
        assertEquals("speaker-wespeaker-r34lm-fp16.onnx", r[PackId.SPEAKERS].files.single().name)
        // Old installs keep these names in filesDir/models: adoption finds them there.
        assertEquals("ggml-ivrit-large-v3-turbo-q5_0.bin", r[PackId.SPEECH_HE].files.single().name)
        assertEquals("ggml-large-v3-turbo-q5_0.bin", r[PackId.SPEECH_ANY].files.single().name)
    }

    /**
     * Only the multilingual speech model is unpinned for now (until its mirror is published; see
     * models.yml): from Hugging Face, checked by size as before the registry. Every other pack is
     * SHA-256-pinned.
     */
    @Test
    fun onlyMultilingualSpeechIsUnpinned() {
        val r = shipped()
        assertEquals(setOf(PackId.SPEECH_ANY), r.packs.filter { it.unpinned }.map { it.id }.toSet())
        val f = r[PackId.SPEECH_ANY].files.single()
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", f.url)
        assertFalse(f.pinned)
        assertEquals(10_000_000L, f.minSize)
        assertEquals(574_000_000L, r[PackId.SPEECH_ANY].bytes)
        assertNull(r[PackId.SPEECH_ANY].pending)
        for (id in listOf(PackId.FACES, PackId.PEOPLE, PackId.SPEAKERS, PackId.SPEECH_HE)) assertTrue(r[id].files.all { it.pinned })
    }

    /** Selfie mode's pack: one small optional file (MediaPipe's BlazeFace full range), pinned by version and SHA-256. */
    @Test
    fun selfiePackIsOneOptionalPinnedFile() {
        val p = shipped()[PackId.SELFIE]
        assertNull(p.pending)
        assertFalse(p.unpinned)
        assertFalse("downloaded when asked for, not at the first launch", p.recommended)
        val f = p.files.single()
        assertEquals("blaze_face_full_range.tflite", f.name)
        assertEquals(1_083_786L, f.size)
        assertTrue(f.pinned)
        assertEquals("3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b", f.sha256)
        assertEquals("https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_full_range/float16/1/blaze_face_full_range.tflite", f.url)
        assertEquals(PackId.SELFIE, shipped().packOf("blaze_face_full_range.tflite")?.id)
    }

    @Test
    fun packOfFindsTheOwner() {
        val r = shipped()
        assertEquals(PackId.FACES, r.packOf("retinaface_mv2.onnx")?.id)
        assertEquals(PackId.PEOPLE, r.packOf("lraspp_mbv3_288x512_fp16.onnx")?.id)
        assertNull(r.packOf("nothing.onnx"))
    }

    private fun one(file: String, pack: String = "faces", extra: String = ""): String =
        """{"schema":1,"packs":[{"id":"$pack","version":1,"compat":1$extra,"files":[$file]}]}"""

    private val okFile = """{"name":"a.onnx","size":10,"sha256":"${"a".repeat(64)}","url":"https://example.com/a.onnx"}"""

    private fun rejects(json: String) {
        try {
            ModelRegistry.parse(json)
            fail("accepted: $json")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun readerRejectsUnsafeEntries() {
        assertEquals(1, ModelRegistry.parse(one(okFile)).packs.size)
        rejects(one(okFile.replace("https://", "http://")))
        rejects(one(okFile.replace("a.onnx\",\"size", "../a.onnx\",\"size")))
        rejects(one(okFile.replace("a.onnx\",\"size", "sub/a.onnx\",\"size")))
        rejects(one(okFile.replace("a.onnx\",\"size", ".hidden\",\"size")))
        rejects(one(okFile.replace("a".repeat(64), "z".repeat(64))))
        rejects(one(okFile.replace("\"size\":10", "\"size\":0")))
        rejects(one(okFile, pack = "unknown"))
        rejects(one("$okFile,$okFile"))
        rejects("""{"schema":1,"packs":[{"id":"faces","version":1,"compat":2,"files":[$okFile]}]}""")
    }

    @Test
    fun onlySpeechMayBeUnpinned() {
        val loose = """{"name":"c.bin","size":0,"sha256":"","minSize":10,"approxSize":100,"url":"https://example.com/c.bin"}"""
        assertTrue(ModelRegistry.parse(one(loose, pack = "speech-any", extra = ",\"unpinned\":true"))[PackId.SPEECH_ANY].unpinned)
        for (pack in listOf("faces", "people", "speakers", "selfie")) rejects(one(loose, pack = pack, extra = ",\"unpinned\":true"))
        // Without a smallest size it can't be checked at all.
        rejects(one(loose.replace("\"minSize\":10,", ""), pack = "speech-any", extra = ",\"unpinned\":true"))
        // Not marked unpinned: it needs its size and checksum.
        rejects(one(loose, pack = "speech-any"))
        rejects(one(loose.replace("https://", "http://"), pack = "speech-any", extra = ",\"unpinned\":true"))
    }

    @Test
    fun pendingPackNeedsNoChecksumYet() {
        val pending = """{"name":"b.bin","size":0,"sha256":"","url":"https://example.com/b.bin"}"""
        val r = ModelRegistry.parse(one(pending, pack = "speech-any", extra = ",\"pending\":\"not mirrored\""))
        assertEquals("not mirrored", r[PackId.SPEECH_ANY].pending)
        rejects(one(pending, pack = "speech-any"))
        assertFalse(ModelRegistry.isSha256("A".repeat(64)))
    }

    // ------------------------------------------------------------------ licences

    /** Where a bundled licence text lives: the shared texts (shared/assets) or the app's own assets (the fonts'). */
    private fun asset(rel: String): File? =
        listOf("../shared/assets", "shared/assets", "src/main/assets", "app/src/main/assets").map { File(it, rel) }.firstOrNull { it.isFile }

    /** Every file of every pack says what it is licensed under, and its full text is bundled (offline). */
    @Test
    fun everyFileHasItsLicenceAndAnExistingText() {
        for (p in shipped().packs) {
            assertTrue("pack ${p.id.key}: licenceVersion", p.licenceVersion >= 1)
            for (f in p.files) {
                val l = f.licence
                assertTrue("${f.name}: title", l.title.isNotBlank())
                assertTrue("${f.name}: licence", l.licence.isNotBlank())
                assertTrue("${f.name}: copyright", l.copyright.isNotBlank())
                assertTrue("${f.name}: source", l.source.startsWith("https://"))
                assertTrue("${f.name}: licenceFile", l.licenceFile.isNotBlank())
                val text = asset(l.licenceFile)
                assertNotNull("${f.name}: licence file ${l.licenceFile} not found", text)
                assertTrue("${f.name}: licence file is empty", text!!.length() > 500)
            }
        }
    }

    /** Today every model is MIT, Apache-2.0, BSD-3-Clause or CC BY 4.0 (attribution only): none asks for acceptance. A restricted one would set it. */
    @Test
    fun noShippedModelNeedsAcceptanceYet() {
        for (p in shipped().packs) assertFalse(p.id.key, p.requiresAcceptance)
    }

    @Test
    fun licenceFieldsAreRead() {
        val file = """{"name":"a.onnx","size":10,"sha256":"${"a".repeat(64)}","url":"https://example.com/a.onnx",""" +
            """"title":"A","licence":"Non-commercial","copyright":"(c) X","source":"https://example.com","licenceFile":"licences/X.txt","requiresAcceptance":true,"note":"n","unverified":"u"}"""
        val r = ModelRegistry.parse(one(file, extra = ",\"licenceVersion\":3"))
        val pack = r[PackId.FACES]
        assertEquals(3, pack.licenceVersion)
        assertTrue(pack.requiresAcceptance)
        val l = pack.files.single().licence
        assertEquals(LicenceInfo("A", "Non-commercial", "(c) X", "https://example.com", "licences/X.txt", true, "n", "u"), l)
        // A file without licence fields (a test's own registry) needs no acceptance.
        assertFalse(ModelRegistry.parse(one(okFile))[PackId.FACES].requiresAcceptance)
        // The text must be a plain path inside the assets.
        rejects(one(file.replace("licences/X.txt", "../X.txt")))
        rejects(one(file.replace("licences/X.txt", "/etc/passwd")))
        rejects(one(file, extra = ",\"licenceVersion\":0"))
    }

    @Test
    fun sameTermsAreOneEntry() {
        val faces = LicenceText.group(shipped()[PackId.FACES].files)
        // RetinaFace's ONNX and its two GPU files share terms; YuNet is another holder's.
        assertEquals(listOf("RetinaFace (MobileNetV2)", "YuNet (OpenCV Zoo)"), faces.map { it.title })
        assertTrue(faces[0].note.contains("Converted to TFLite"))
        assertEquals(listOf("PP-HumanSeg (OpenCV Zoo)", "LR-ASPP MobileNetV3"), LicenceText.group(shipped()[PackId.PEOPLE].files).map { it.title })
    }

    /** The "Open-source licences" list: components.json parses, ids are unique, each text is bundled. */
    @Test
    fun componentsListIsComplete() {
        val json = (asset("licences/components.json") ?: error("components.json not found")).readText()
        val list = LicenceCatalog.parseComponents(json)
        assertTrue(list.size > 20)
        val ids = list.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (name in listOf("ONNX Runtime", "OpenCV", "LiteRT", "MediaPipe", "whisper.cpp", "Media3", "Transformers.js", "Mediabunny", "Preact")) {
            assertTrue("$name listed", list.any { it.info.title.startsWith(name) })
        }
        assertEquals(10, list.count { it.kind == "font" })
        for (e in list) {
            assertTrue("${e.id}: licence", e.info.licence.isNotBlank())
            if (e.info.licenceFile.isNotBlank()) assertNotNull("${e.id}: ${e.info.licenceFile} not found", asset(e.info.licenceFile))
            else assertTrue("${e.id}: no text and no reason", e.info.unverified.isNotBlank())
        }
        // Models: the registry's, grouped, then the screen adds ML Kit's.
        assertEquals(setOf("faces-0", "faces-1", "people-0", "people-1", "speakers-0", "selfie-0", "speech-he-0", "speech-any-0"), LicenceCatalog.models(shipped()).map { it.id }.toSet())
        assertTrue(list.any { it.id == "mlkit" && it.kind == "model" })
    }
}
