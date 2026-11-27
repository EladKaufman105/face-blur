package com.elad.faceblur.models

import org.json.JSONObject

/** The packs of models the app downloads, by their id in the registry. */
enum class PackId(val key: String) {
    FACES("faces"),
    SELFIE("selfie"),
    PEOPLE("people"),
    SPEAKERS("speakers"),
    SPEECH_HE("speech-he"),
    SPEECH_ANY("speech-any"),
    ;

    companion object {
        fun of(key: String): PackId? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a model file is licensed under, from the registry (the licences screens, and the consent
 * before a download). The upstream's own terms, never worded by us: [licence] is an SPDX id or the
 * terms' name, [copyright] the holder's notice, [source] where it was checked, [licenceFile] the
 * full text bundled in the assets (offline, `licences/...`), [requiresAcceptance] true for terms
 * with restrictions beyond MIT / Apache-2.0 (the user must accept them before the download).
 * @param title the model's name, as the licence screens show it.
 * @param note a fact the licence asks us to state (a change made to the model), English only.
 * @param unverified why a field couldn't be confirmed from the upstream (a note for developers; the
 *   screens don't show it): empty when everything was checked.
 */
data class LicenceInfo(
    val title: String,
    val licence: String,
    val copyright: String,
    val source: String,
    val licenceFile: String,
    val requiresAcceptance: Boolean = false,
    val note: String = "",
    val unverified: String = "",
) {
    companion object {
        /** A file with no licence entry (only in tests' own registries: the shipped one is checked). */
        val NONE = LicenceInfo("", "", "", "", "")
    }
}

/**
 * One file of a pack: where it comes from (a pinned URL) and what it must be (size, SHA-256).
 * An unpinned file (speech models only, see [Pack.unpinned]) has no size or checksum: it must be
 * at least [minSize] bytes, and [approxSize] is shown and used for the mobile-data rule.
 */
data class ModelFile(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val minSize: Long = 0L,
    val approxSize: Long = 0L,
    val licence: LicenceInfo = LicenceInfo.NONE,
) {
    /** Checked by its SHA-256 (false: an unpinned file, checked by its size only). */
    val pinned: Boolean get() = sha256.isNotEmpty()

    /** The size to show and plan with. */
    val expectedSize: Long get() = if (size > 0) size else approxSize
}

/**
 * A pack: the files one feature needs, all or nothing.
 * @param version bumped when a file changes (the installed one then has an update).
 * @param compat the oldest installed version the app still runs; older ones count as missing.
 * @param pending why the pack can't be downloaded yet (its files have no checksum); null: it can.
 * @param unpinned downloaded from a URL that isn't pinned and checked by size only, as before the
 *   registry (allowed for a speech model only, never for faces, people or speakers).
 * @param licenceVersion bumped when the terms of any file change: an acceptance of an older one
 *   no longer counts (see [LicenceConsent]). It asks again at the next download or update, not for a
 *   pack that is already installed (raising only this version changes nothing there: bump [version]
 *   too if installed packs must be updated).
 */
data class Pack(
    val id: PackId,
    val version: Int,
    val compat: Int,
    val recommended: Boolean,
    val files: List<ModelFile>,
    val pending: String? = null,
    val unpinned: Boolean = false,
    val licenceVersion: Int = 1,
) {
    /** The download size of the whole pack. */
    val bytes: Long get() = files.sumOf { it.expectedSize }

    /** Some file's terms must be accepted before the pack is downloaded. */
    val requiresAcceptance: Boolean get() = files.any { it.licence.requiresAcceptance }
}

/**
 * The one list of every model the app downloads (assets/model-registry.json): read by the app,
 * by ci/fetch-models.sh and by a unit test. Each URL pins a commit (or an immutable release), and
 * each file's size and SHA-256 are checked after the download and before every use of the faces.
 */
class ModelRegistry(val packs: List<Pack>) {

    operator fun get(id: PackId): Pack = packs.first { it.id == id }

    fun find(id: PackId): Pack? = packs.firstOrNull { it.id == id }

    /** The pack a file belongs to (file names are unique across packs: they share one folder). */
    fun packOf(fileName: String): Pack? = packs.firstOrNull { p -> p.files.any { it.name == fileName } }

    companion object {
        /** The registry's file in the app's assets. */
        const val ASSET = "model-registry.json"

        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        /** A file's licence entry (every field optional here: the shipped registry's are checked by RegistryTest). */
        private fun licenceOf(f: JSONObject) = LicenceInfo(
            title = f.optString("title", ""),
            licence = f.optString("licence", ""),
            copyright = f.optString("copyright", ""),
            source = f.optString("source", ""),
            licenceFile = f.optString("licenceFile", "").also { require(it.isEmpty() || LICENCE_FILE.matches(it)) { "bad licence file $it" } },
            requiresAcceptance = f.optBoolean("requiresAcceptance", false),
            note = f.optString("note", ""),
            unverified = f.optString("unverified", ""),
        )

        /** A bundled text: a plain relative path inside the assets (no "..", no leading slash). */
        private val LICENCE_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*")

        fun isSha256(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

        /**
         * Reads the registry. Throws [IllegalArgumentException] on anything unsafe or unknown: a
         * pack id the app doesn't know, a file name that isn't a plain name, a URL that isn't
         * HTTPS, a name used twice, or a file without its size and checksum (unless the pack is
         * marked pending).
         */
        fun parse(json: String): ModelRegistry {
            val root = JSONObject(json)
            require(root.optInt("schema") == 1) { "unknown registry schema" }
            val arr = root.getJSONArray("packs")
            val packs = ArrayList<Pack>()
            val names = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.getString("id")
                val id = requireNotNull(PackId.of(key)) { "unknown pack $key" }
                require(packs.none { it.id == id }) { "pack $key twice" }
                val pending = o.optString("pending", "").takeIf { it.isNotBlank() }
                val unpinned = o.optBoolean("unpinned", false)
                // Only a speech model may come unpinned (checked by size): never what face blur, people or speakers run on.
                require(!unpinned || id == PackId.SPEECH_HE || id == PackId.SPEECH_ANY) { "pack $key may not be unpinned" }
                val fa = o.getJSONArray("files")
                val files = ArrayList<ModelFile>()
                for (j in 0 until fa.length()) {
                    val f = fa.getJSONObject(j)
                    val name = f.getString("name")
                    val url = f.getString("url")
                    val size = f.optLong("size", 0L)
                    val sha = f.optString("sha256", "").lowercase()
                    val minSize = f.optLong("minSize", 0L)
                    val approxSize = f.optLong("approxSize", 0L)
                    require(NAME.matches(name) && ".." !in name) { "bad file name $name" }
                    require(names.add(name)) { "file $name twice" }
                    require(url.startsWith("https://")) { "$name: not an https URL" }
                    if (unpinned) {
                        require(minSize > 0 && approxSize > 0) { "$name: unpinned without minSize and approxSize" }
                        require(sha.isEmpty() || isSha256(sha)) { "$name: bad sha256" }
                    } else if (pending == null) {
                        require(size > 0) { "$name: no size" }
                        require(isSha256(sha)) { "$name: no sha256" }
                    }
                    files += ModelFile(name, url, size, sha, minSize, approxSize, licenceOf(f))
                }
                require(files.isNotEmpty()) { "pack $key has no files" }
                val version = o.optInt("version", 1)
                val compat = o.optInt("compat", 1)
                require(version >= 1 && compat in 1..version) { "pack $key: bad version/compat" }
                val licenceVersion = o.optInt("licenceVersion", 1)
                require(licenceVersion >= 1) { "pack $key: bad licenceVersion" }
                packs += Pack(id, version, compat, o.optBoolean("recommended", false), files, pending, unpinned, licenceVersion)
            }
            return ModelRegistry(packs)
        }
    }
}
