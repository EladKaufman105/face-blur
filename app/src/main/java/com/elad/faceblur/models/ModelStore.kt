package com.elad.faceblur.models

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** What is installed of a pack. */
enum class Installed {
    /** Not on the device. */
    MISSING,

    /** Every file is the registry's. */
    READY,

    /** An older version that the app still runs; the new one downloads on request. */
    UPDATE_AVAILABLE,

    /** Too old for this app: counts as missing ("update the model to use…"). */
    NEEDS_UPDATE,

    /** Can't be downloaded yet (the registry has no checksum for it). */
    PENDING,
}

/** A download of a pack, while it is queued or runs, or after it failed. */
sealed interface Job {
    data object Queued : Job

    /** Waiting for a connection ([wifi]: for Wi-Fi, by the user's rule or choice). */
    data class Waiting(val wifi: Boolean) : Job

    data class Downloading(val done: Long, val total: Long) : Job {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** Checking the files' SHA-256. */
    data object Verifying : Job

    data class Failed(val reason: FailReason) : Job
}

enum class FailReason { NO_SPACE, NETWORK, CHECKSUM, SERVER }

/** A pack and where it stands. */
data class PackStatus(val pack: Pack, val installed: Installed, val job: Job?) {
    /** Can be used now (verified when installed, and checked again before each use). */
    val usable: Boolean get() = installed == Installed.READY || installed == Installed.UPDATE_AVAILABLE

    /** Queued, downloading or verifying. */
    val busy: Boolean get() = job is Job.Queued || job is Job.Downloading || job is Job.Verifying

    val waiting: Boolean get() = job is Job.Waiting

    /** Something can be downloaded: missing, too old, has an update, or failed. */
    val downloadable: Boolean get() = installed != Installed.PENDING && installed != Installed.READY && !busy && !waiting
}

/** A feature needs a pack that is not on the device (or not whole, or changed): it doesn't run. */
class ModelMissing(val pack: PackId) : IOException("model pack ${pack.key} is not on the device")

/**
 * The models on the device, in filesDir/models (not backed up), one pack at a time: installed
 * only when every file of the pack passed its size and SHA-256 check, and checked again before
 * each use ([require]). installed.json there says which packs are installed, in which version,
 * with each file's size and checksum.
 *
 * Old versions of the app copied the bundled models to filesDir and downloaded others to
 * filesDir/models: [migrate] adopts every one whose checksum is the registry's.
 */
class ModelStore private constructor(context: Context) {
    private val app = context.applicationContext
    val dir: File = File(app.filesDir, "models").apply { mkdirs() }
    private val shipped: ModelRegistry = ModelRegistry.parse(app.assets.open(ModelRegistry.ASSET).bufferedReader().use { it.readText() })

    /** The registry the app ships (tests may stand a small file in for a speech model). */
    @Volatile var registry: ModelRegistry = shipped
        private set
    private val manifest = File(dir, "installed.json")
    private val lock = Any()

    private class Entry(val version: Int, val files: Map<String, Pair<Long, String>>)

    private val installed = HashMap<PackId, Entry>()
    private val jobs = HashMap<PackId, Job>()
    private val inUse = HashMap<PackId, Int>()
    /** Updates that are downloaded and checked, waiting for the pack to be released. */
    private val pendingSwitch = HashSet<PackId>()

    /** Every pack's status, for the screens (recomposes when anything changes). */
    var statuses by mutableStateOf<Map<PackId, PackStatus>>(emptyMap())
        private set

    /** Files from older versions of the app have been checked (the first-launch sheet waits for it). */
    var migrated by mutableStateOf(false)
        private set

    init {
        load()
        publish()
    }

    fun status(id: PackId): PackStatus = synchronized(lock) { statusOf(registry[id]) }

    /** The pack is usable now (cheap: from the installed list; [require] checks the files). */
    fun isUsable(id: PackId): Boolean = status(id).usable

    private fun statusOf(p: Pack): PackStatus {
        val e = installed[p.id]
        val inst = when {
            e == null -> if (p.pending != null) Installed.PENDING else Installed.MISSING
            e.version < p.compat -> Installed.NEEDS_UPDATE
            p.files.all { f -> e.files[f.name]?.second == f.sha256 } -> Installed.READY
            else -> Installed.UPDATE_AVAILABLE
        }
        return PackStatus(p, inst, jobs[p.id])
    }

    private fun publish() {
        // Assigned under the lock: two threads publishing can't leave the older map in place.
        synchronized(lock) { statuses = registry.packs.associate { it.id to statusOf(it) } }
    }

    // ------------------------------------------------------------------ use

    /**
     * The pack's files, each checked now (size, and SHA-256 for all but the large speech models),
     * by file name. Throws [ModelMissing] if the pack isn't installed, is too old, or a file is
     * gone or changed (the pack is then forgotten, and asks to be downloaded again).
     */
    fun require(id: PackId): Map<String, File> {
        val p = registry[id]
        val e = synchronized(lock) { installed[id] } ?: throw ModelMissing(id)
        if (e.version < p.compat) throw ModelMissing(id)
        val out = LinkedHashMap<String, File>()
        val rehash = e.files.values.sumOf { it.first } <= REHASH_LIMIT
        for ((name, sizeSha) in e.files) {
            val f = File(dir, name)
            val ok = f.isFile && f.length() == sizeSha.first && (!rehash || Hashing.sha256(f) == sizeSha.second)
            if (!ok) {
                Log.w(TAG, "model $name changed or gone: pack ${id.key} must be downloaded again")
                forget(id)
                throw ModelMissing(id)
            }
            out[name] = f
        }
        return out
    }

    /** [require], and the pack counts as in use (it can't be deleted) until [release]. */
    fun acquire(id: PackId): Map<String, File> {
        // Counted before the check: a delete can't slip in between the check and the use.
        synchronized(lock) { inUse[id] = (inUse[id] ?: 0) + 1 }
        try {
            return require(id)
        } catch (t: Throwable) {
            release(id)
            throw t
        }
    }

    fun release(id: PackId) {
        var switch = false
        synchronized(lock) {
            val n = (inUse[id] ?: 0) - 1
            if (n <= 0) inUse.remove(id) else inUse[id] = n
            switch = n <= 0 && id in pendingSwitch
        }
        // An update that came in while the pack was in use goes in now.
        if (switch) finishUpdate(id)
    }

    /** Where an update's new file waits while the pack is in use (see [finishUpdate]). */
    fun stagedFile(name: String): File = File(dir, "$name.new")

    /**
     * An update of an installed pack is downloaded (its changed files as [stagedFile]s, checked):
     * switched in now if nothing uses the pack, else when it is released. Files in use are never
     * overwritten. True when the update is (or will be) in.
     */
    fun finishUpdate(id: PackId): Boolean {
        val p = registry[id]
        synchronized(lock) {
            for (f in p.files) {
                val cur = File(dir, f.name)
                val staged = stagedFile(f.name)
                if (!staged.isFile && !(if (f.pinned) cur.length() == f.size else cur.length() >= f.minSize)) return false
            }
            if ((inUse[id] ?: 0) > 0) {
                pendingSwitch += id
                return true
            }
            pendingSwitch -= id
            for (f in p.files) {
                val staged = stagedFile(f.name)
                if (staged.isFile) Files.move(staged.toPath(), File(dir, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        return markInstalled(id)
    }

    fun inUse(id: PackId): Boolean = synchronized(lock) { (inUse[id] ?: 0) > 0 }

    /** Where a model file is kept (no check: [require] checks). */
    fun file(name: String): File = File(dir, name)

    // ------------------------------------------------------------------ change

    /**
     * Deletes the pack's files. Refused (false) while the pack is in use or downloading.
     */
    fun delete(id: PackId): Boolean {
        synchronized(lock) {
            if ((inUse[id] ?: 0) > 0) return false
            val j = jobs[id]
            if (j is Job.Queued || j is Job.Downloading || j is Job.Verifying || j is Job.Waiting) return false
            val names = registry[id].files.map { it.name }.toSet() + installed[id]?.files?.keys.orEmpty()
            for (n in names) {
                File(dir, n).delete()
                File(dir, "$n.part").delete()
                stagedFile(n).delete()
                File(dir, "$n.new.part").delete()
            }
            pendingSwitch -= id
            installed.remove(id)
            jobs.remove(id)
            save()
        }
        publish()
        return true
    }

    /** The pack is installed only if every file is there whole: its entry, from the registry's files. */
    fun markInstalled(id: PackId): Boolean {
        val p = registry[id]
        if (p.pending != null) return false
        synchronized(lock) {
            // Pinned files were hashed on the way in; an unpinned one is checked by its size, as before the registry.
            if (!p.files.all { f -> File(dir, f.name).let { if (f.pinned) it.length() == f.size else it.length() >= f.minSize } }) return false
            installed[id] = Entry(p.version, p.files.associate { it.name to (File(dir, it.name).length() to it.sha256) })
            save()
        }
        publish()
        return true
    }

    private fun forget(id: PackId) {
        synchronized(lock) {
            installed.remove(id)
            save()
        }
        publish()
    }

    internal fun setJob(id: PackId, job: Job?) {
        synchronized(lock) {
            if (job == null) jobs.remove(id) else jobs[id] = job
        }
        publish()
    }

    /**
     * Installs the pack from local copies of its files (tests, the release self-test), through
     * the same check as a download: each one copied to a part file, its size and SHA-256 checked,
     * then renamed into place. Files already in place and right are kept. True when the pack is whole.
     */
    fun install(id: PackId, source: (String) -> File?): Boolean {
        val p = registry[id]
        if (p.pending != null) return false
        for (m in p.files) {
            val dest = File(dir, m.name)
            if (Hashing.matches(dest, m)) continue
            val src = source(m.name) ?: return false
            val part = File(dir, m.name + ".part")
            src.copyTo(part, overwrite = true)
            if (!Hashing.matches(part, m)) {
                part.delete()
                Log.w(TAG, "${m.name}: not the registry's file")
                return false
            }
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return markInstalled(id)
    }

    /**
     * Tests only: [p] stands in for its pack (Whisper's tiny model for a speech model, which CI
     * doesn't download). It goes through the same install and checks. Never the faces.
     */
    @androidx.annotation.VisibleForTesting
    internal fun replacePackForTest(p: Pack) {
        require(p.id != PackId.FACES) { "the face pack is never replaced" }
        registry = ModelRegistry(registry.packs.map { if (it.id == p.id) p else it })
        publish()
    }

    @androidx.annotation.VisibleForTesting
    internal fun restoreRegistryForTest() {
        registry = shipped
        publish()
    }

    // ------------------------------------------------------------------ old installs

    /**
     * Adopts model files left by older versions of the app (blocking; run once at start, off the
     * main thread): in filesDir/models (GPU faces, LR-ASPP, speakers, Whisper) or in filesDir (the
     * bundled models copied out on first use). A file counts only if its SHA-256 is the
     * registry's; a pack is installed when all its files are there. Old copies that don't match
     * are deleted (they are older models this version doesn't use).
     */
    fun migrate() {
        try {
            for (p in registry.packs) {
                if (p.pending != null) continue
                if (synchronized(lock) { installed.containsKey(p.id) }) continue
                var whole = true
                for (m in p.files) {
                    val dest = File(dir, m.name)
                    // An unpinned speech model is adopted as before: there, and big enough.
                    if (Hashing.matches(dest, m)) continue
                    val old = File(app.filesDir, m.name)
                    if (m.pinned && old.length() == m.size && Hashing.matches(old, m)) {
                        Files.move(old.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        continue
                    }
                    whole = false
                }
                if (whole) {
                    markInstalled(p.id)
                    Log.i(TAG, "adopted model pack ${p.id.key}")
                }
            }
            // What older versions copied out of the APK and doesn't match: not used any more.
            for (name in LEGACY_COPIES) {
                File(app.filesDir, name).delete()
                File(app.filesDir, "$name.tmp").delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "model migration failed", e)
        } finally {
            migrated = true
        }
    }

    // ------------------------------------------------------------------ installed.json

    private fun load() {
        val j = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return
        synchronized(lock) {
            for (key in j.keys()) {
                val id = PackId.of(key) ?: continue
                val o = j.optJSONObject(key) ?: continue
                val fo = o.optJSONObject("files") ?: continue
                val files = HashMap<String, Pair<Long, String>>()
                for (name in fo.keys()) {
                    val f = fo.optJSONObject(name) ?: continue
                    files[name] = f.optLong("size") to f.optString("sha256")
                }
                // A file that's gone or another size: the pack isn't installed any more.
                if (files.isEmpty() || files.any { (n, s) -> File(dir, n).length() != s.first }) continue
                installed[id] = Entry(o.optInt("version", 1), files)
            }
        }
    }

    /** Called under [lock]. Written whole to a temp file, then renamed. */
    private fun save() {
        val j = JSONObject()
        for ((id, e) in installed) {
            val fo = JSONObject()
            for ((n, s) in e.files) fo.put(n, JSONObject().put("size", s.first).put("sha256", s.second))
            j.put(id.key, JSONObject().put("version", e.version).put("files", fo))
        }
        try {
            val tmp = File(dir, "installed.json.tmp")
            tmp.writeText(j.toString())
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Log.w(TAG, "installed.json not saved", e)
        }
    }

    companion object {
        private const val TAG = "ONIT"

        /** Packs up to this size are hashed again before each use (the speech models only by size). */
        private const val REHASH_LIMIT = 64L * 1_000_000L

        /** The models older versions copied out of the APK into filesDir. */
        private val LEGACY_COPIES = listOf("retinaface_mv2.onnx", "face_detection_yunet_2023mar.onnx", "human_segmentation_pphumanseg_2023mar.onnx")

        @Volatile private var instance: ModelStore? = null
        private val migration = Executors.newSingleThreadExecutor { r -> Thread(r, "model-migrate").apply { priority = Thread.MIN_PRIORITY } }

        /** The store (made once; adopting older files starts in the background then). */
        fun get(context: Context): ModelStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ModelStore(context).also { s ->
                    instance = s
                    migration.execute { s.migrate() }
                }
            }
        }
    }
}
