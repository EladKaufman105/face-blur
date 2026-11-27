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
}
