package com.elad.faceblur.project

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.elad.faceblur.analysis.AnalysisResult
import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.Det
import com.elad.faceblur.analysis.FrameStore
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.effects.Background
import com.elad.faceblur.edit.Cue
import com.elad.faceblur.edit.EditState
import com.elad.faceblur.edit.EditTrack
import com.elad.faceblur.edit.SfxClip
import com.elad.faceblur.edit.Subtitles
import com.elad.faceblur.edit.TimedWord
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A saved project, as listed on the home screen. */
class ProjectMeta(
    val id: String,
    val name: String,
    val uri: Uri,
    val created: Long,
    val updated: Long,
    val durationMs: Long,
    val subtitlesOnly: Boolean,
    val blurs: Int,
    val cues: Int,
    /** Space it takes on the phone. */
    val bytes: Long,
    /** Words bleeped (see [Subtitles.bleepedWordCount]). */
    val bleeped: Int = 0,
    /** The voice is changed (or muted). */
    val voice: Boolean = false,
    /** Sound effects placed. */
    val sounds: Int = 0,
    /** The background is replaced (behind the people or a green / blue screen). */
    val background: Boolean = false,
    /** Stretches cut out of the video. */
    val removed: Int = 0,
    /**
     * Saved before project.json kept [bleeped], [sounds] and [removed]: they were read from the
     * edits (see [ProjectStore.keepCounts]).
     */
    val countsFromEdits: Boolean = false,
    /** The batch it was made in (projects of one batch are shown as one folder); null: not from a batch. */
    val batchId: String? = null,
    /** When that batch started (the folder's default name). */
    val batchAt: Long = 0L,
    /** The folder's own name if it was renamed; null: named by its date and time. */
    val batchName: String? = null,
)

/** A project read back: its settings (JSON), the analysis, and the edits. */
class LoadedProject(val meta: ProjectMeta, val json: JSONObject, val analysis: AnalysisResult, val edits: EditState?)

/**
 * Projects in the app's own storage, one folder each:
 * project.json (video, settings, look), analysis.bin (what the analysis found), edits.bin
 * (blurs, subtitles, bleeps), frames.bin (the analysis frames, for tracking by hand) and thumb.jpg.
 * Nothing of the video itself is copied; it's read from where it is.
 */
object ProjectStore {
    private const val ANALYSIS_VERSION = 1
    private const val EDITS_VERSION = 5

    fun root(context: Context) = File(context.filesDir, "projects")

    fun dir(context: Context, id: String) = File(root(context), id)

    fun thumb(context: Context, id: String) = File(dir(context, id), "thumb.jpg")

    fun newId(): String = System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().take(6)

    /** Saved projects, last edited first. */
    fun list(context: Context): List<ProjectMeta> {
        val all = (root(context).listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { d -> runCatching { meta(d.name, readJson(d), d) }.getOrNull() }
            .sortedByDescending { it.updated }
        countsRead.keys.retainAll(all.mapTo(HashSet()) { it.id })
        return all
    }

    /**
     * Read-modify-write of project.json (saves, renames, relinks, [keepCounts]) one at a time, so
     * none of them loses another's change.
     */
    private val jsonLock = Any()

    private fun readJson(d: File) = JSONObject(File(d, "project.json").readText())

    /** [edits], when they're read anyway, spare reading them again for a project saved before its counts were kept. */
    private fun meta(id: String, o: JSONObject, d: File, edits: EditState? = null): ProjectMeta {
        val old = if (o.has("sounds")) null else edits?.let { countsOf(it) } ?: oldCounts(id, d)
        return ProjectMeta(
            id = id,
            name = o.optString("name"),
            uri = Uri.parse(o.getString("uri")),
            created = o.optLong("created"),
            updated = o.optLong("updated"),
            durationMs = o.optLong("durationMs"),
            subtitlesOnly = o.optBoolean("subtitlesOnly"),
            blurs = o.optInt("blurs"),
            cues = o.optInt("cues"),
            bytes = d.listFiles()?.sumOf { it.length() } ?: 0L,
            bleeped = if (o.has("bleeped")) o.optInt("bleeped") else old?.bleeped ?: 0,
            voice = AudioEdit.fromJson(o.optJSONObject("audio")).voice != VoiceFx.NONE,
            sounds = if (o.has("sounds")) o.optInt("sounds") else old?.sounds ?: 0,
            background = Background.fromJson(o.optJSONObject("background")).active,
            removed = if (o.has("removed")) o.optInt("removed") else old?.removed ?: 0,
            countsFromEdits = old != null,
            batchId = o.optString("batchId").takeIf { it.isNotBlank() },
            batchAt = o.optLong("batchAt"),
            batchName = o.optString("batchName").takeIf { it.isNotBlank() },
        )
    }

    /** What the list shows of a project's edits besides blurs and subtitles (kept in project.json). */
    private class Counts(val bleeped: Int, val sounds: Int, val removed: Int) {
        fun putIn(o: JSONObject) {
            o.put("bleeped", bleeped)
            o.put("sounds", sounds)
            o.put("removed", removed)
        }
    }

    private fun countsOf(e: EditState) = Counts(Subtitles.bleepedWordCount(e.cues, e.bleeps), e.sfx.size, e.removed.size)

    /** [Counts] read from a project's edits.bin, by its id, with the time the file was written. */
    private val countsRead = ConcurrentHashMap<String, Pair<Long, Counts>>()

    /**
     * The counts of a project saved before project.json kept them, from its edits.bin (read once,
     * until the file changes). Null without edits.
     */
    private fun oldCounts(id: String, d: File): Counts? {
        val f = File(d, "edits.bin")
        if (!f.exists()) return null
        val stamp = f.lastModified()
        countsRead[id]?.let { (at, c) -> if (at == stamp) return c }
        val e = runCatching { readEdits(f) }.getOrNull() ?: return null
        return countsOf(e).also { countsRead[id] = stamp to it }
    }

    /**
     * Keeps in project.json the counts of a project saved before it had them (read from its
     * edits), so listing it no longer reads them. Only adds what's missing; nothing else changes.
     * Not for the open project (its own saves keep them).
     */
    fun keepCounts(context: Context, id: String) {
        val d = dir(context, id)
        synchronized(jsonLock) {
            if (!d.exists()) return
            val json = readJson(d)
            if (json.has("sounds")) return
            val c = oldCounts(id, d) ?: return
            c.putIn(json)
            atomic(File(d, "project.json")) { it.writeText(json.toString()) }
        }
    }

    /**
     * Starts a project for a new editing session: keeps read access to the video, writes the
     * analysis, moves the analysis frames in and saves a thumbnail. [json] is the first save.
     */
    fun create(context: Context, id: String, analysis: AnalysisResult, thumbnail: Bitmap?, json: JSONObject, edits: EditState) {
        val d = dir(context, id).apply { mkdirs() }
        keepAccess(context, Uri.parse(json.getString("uri")))
        val framesOk = analysis.frames?.moveTo(File(d, "frames.bin")) == true
        atomic(File(d, "analysis.bin")) { writeAnalysis(it, analysis, framesOk) }
        thumbnail?.let { bmp ->
            runCatching {
                val s = 320f / maxOf(bmp.width, bmp.height)
                val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
                atomic(thumb(context, id)) { f -> FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            }
        }
        save(context, id, json, edits)
    }

    /**
     * Saves the edits, and [fields] into project.json (other fields kept). Each file is replaced
     * whole, so a crash never leaves half a file.
     */
    fun save(context: Context, id: String, fields: JSONObject, edits: EditState) {
        val d = dir(context, id)
        if (!d.exists()) return // deleted meanwhile
        atomic(File(d, "edits.bin")) { writeEdits(it, edits) }
        // What the list shows, so listing reads project.json only.
        val counts = countsOf(edits)
        synchronized(jsonLock) {
            val json = runCatching { readJson(d) }.getOrElse { JSONObject() }
            for (k in fields.keys()) json.put(k, fields.get(k))
            json.put("updated", System.currentTimeMillis())
            json.put("blurs", edits.tracks.size)
            json.put("cues", edits.cues.size)
            counts.putIn(json)
            atomic(File(d, "project.json")) { it.writeText(json.toString()) }
        }
    }

    fun load(context: Context, id: String): LoadedProject {
        val d = dir(context, id)
        val json = readJson(d)
        val analysis = readAnalysis(File(d, "analysis.bin"), File(d, "frames.bin"))
        val edits = File(d, "edits.bin").takeIf { it.exists() }?.let { runCatching { readEdits(it) }.getOrNull() }
        return LoadedProject(meta(id, json, d, edits), json, analysis, edits)
    }

    /**
     * The project's face detection was run after it was made (a subtitles-only project, "Find
     * faces"): the analysis is written again, and the project is no longer subtitles-only.
     */
    fun replaceAnalysis(context: Context, id: String, analysis: AnalysisResult) {
        val d = dir(context, id)
        if (!d.exists()) return
        val framesOk = analysis.frames?.moveTo(File(d, "frames.bin")) == true
        atomic(File(d, "analysis.bin")) { writeAnalysis(it, analysis, framesOk) }
        synchronized(jsonLock) {
            val json = runCatching { readJson(d) }.getOrElse { JSONObject() }
            json.put("subtitlesOnly", false)
            atomic(File(d, "project.json")) { it.writeText(json.toString()) }
        }
    }

    /** Points the project at another copy of its video (the old one can't be read any more). */
    fun relink(context: Context, id: String, uri: Uri) {
        val d = dir(context, id)
        // Outside the lock, so the lock never waits on a system call.
        keepAccess(context, uri)
        val old = synchronized(jsonLock) {
            val json = readJson(d)
            val prev = Uri.parse(json.getString("uri"))
            json.put("uri", uri.toString())
            atomic(File(d, "project.json")) { it.writeText(json.toString()) }
            prev
        }
        releaseIfUnused(context, old)
    }

    fun rename(context: Context, id: String, name: String) {
        val d = dir(context, id)
        synchronized(jsonLock) {
            val json = readJson(d)
            json.put("name", name)
            atomic(File(d, "project.json")) { it.writeText(json.toString()) }
        }
    }

    /** Names the folder of batch [batchId] (every project of it keeps the name; blank: back to its date and time). */
    fun renameBatch(context: Context, batchId: String, name: String) {
        for (id in batchMembers(context, batchId)) {
            val d = dir(context, id)
            synchronized(jsonLock) {
                if (!d.exists()) return@synchronized
                val json = readJson(d)
                if (name.isBlank()) json.remove("batchName") else json.put("batchName", name.trim())
                atomic(File(d, "project.json")) { it.writeText(json.toString()) }
            }
        }
    }

    /** Deletes every project of batch [batchId] (the videos saved in the gallery stay). Returns how many. */
    fun deleteBatch(context: Context, batchId: String): Int {
        val ids = batchMembers(context, batchId)
        for (id in ids) delete(context, id)
        return ids.size
    }

    private fun batchMembers(context: Context, batchId: String): List<String> =
        (root(context).listFiles { f -> f.isDirectory } ?: emptyArray())
            .filter { d -> runCatching { readJson(d).optString("batchId") == batchId }.getOrDefault(false) }
            .map { it.name }

    fun delete(context: Context, id: String) {
        val d = dir(context, id)
        val uri = runCatching { Uri.parse(readJson(d).getString("uri")) }.getOrNull()
        d.deleteRecursively()
        countsRead.remove(id)
        uri?.let { releaseIfUnused(context, it) }
    }

    fun loadThumb(context: Context, id: String): Bitmap? =
        thumb(context, id).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    // ------------------------------------------------------------------ video access

    /** Keeps reading the video after the app restarts (where the picker allows it). */
    private fun keepAccess(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun releaseIfUnused(context: Context, uri: Uri) {
        if (list(context).any { it.uri == uri }) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // ------------------------------------------------------------------ files

    private fun atomic(f: File, write: (File) -> Unit) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        write(tmp)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) error("can't save ${f.name}")
        }
    }

    private fun out(f: File) = DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(f), 1 shl 16), 1 shl 16))

    private fun input(f: File) = DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(f), 1 shl 16), 1 shl 16))

    private fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.str(): String {
        val n = readInt()
        require(n in 0..(1 shl 22))
        val b = ByteArray(n)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun DataOutputStream.box(b: Box) {
        writeFloat(b.cx); writeFloat(b.cy); writeFloat(b.w); writeFloat(b.h); writeFloat(b.angle)
    }

    private fun DataInputStream.box() = Box(readFloat(), readFloat(), readFloat(), readFloat(), readFloat())

    private fun DataOutputStream.path(m: Map<Int, Box>) {
        writeInt(m.size)
        for ((k, b) in m) { writeInt(k); box(b) }
    }

    private fun DataInputStream.path(): TreeMap<Int, Box> {
        val m = TreeMap<Int, Box>()
        repeat(readInt()) { m[readInt()] = box() }
        return m
    }

    fun writeAnalysis(f: File, a: AnalysisResult, withFrames: Boolean) = out(f).use { o ->
        o.writeInt(ANALYSIS_VERSION)
        o.writeInt(a.uprightW); o.writeInt(a.uprightH); o.writeInt(a.rotation); o.writeFloat(a.fps)
        o.writeInt(a.times.size); a.times.forEach(o::writeLong)
        o.writeInt(a.cuts.size); a.cuts.forEach(o::writeInt)
        o.writeInt(a.motion.size); a.motion.forEach(o::writeFloat)
        o.writeInt(a.dets.size)
        for ((frame, ds) in a.dets) {
            o.writeInt(frame)
            o.writeInt(ds.size)
            for (d in ds) { o.box(d.box); o.writeFloat(d.score); o.writeFloat(d.verify) }
        }
        o.writeInt(a.tracks.size)
        for (t in a.tracks) o.path(t)
        val fr = a.frames
        if (withFrames && fr != null) {
            o.writeBoolean(true)
            o.writeInt(fr.width); o.writeInt(fr.height)
            val idx = fr.index()
            o.writeInt(idx.size); idx.forEach(o::writeLong)
        } else {
            o.writeBoolean(false)
        }
    }

    fun readAnalysis(f: File, framesFile: File): AnalysisResult = input(f).use { i ->
        require(i.readInt() == ANALYSIS_VERSION) { "unknown project version" }
        val w = i.readInt(); val h = i.readInt(); val rot = i.readInt(); val fps = i.readFloat()
        val times = LongArray(i.readInt()) { i.readLong() }
        val cuts = IntArray(i.readInt()) { i.readInt() }
        val motion = FloatArray(i.readInt()) { i.readFloat() }
        val dets = HashMap<Int, List<Det>>()
        repeat(i.readInt()) {
            val frame = i.readInt()
            dets[frame] = List(i.readInt()) { Det(i.box(), i.readFloat(), i.readFloat()) }
        }
        val tracks = List(i.readInt()) { i.path() }
        var frames: FrameStore? = null
        if (i.readBoolean()) {
            val fw = i.readInt(); val fh = i.readInt()
            val idx = LongArray(i.readInt()) { i.readLong() }
            frames = FrameStore.open(framesFile, idx, fw, fh)
        }
        AnalysisResult(times, cuts, motion, dets, tracks, w, h, rot, fps, frames)
    }

    fun writeEdits(f: File, e: EditState) = out(f).use { o ->
        o.writeInt(EDITS_VERSION)
        o.writeInt(e.nextId); o.writeInt(e.nextCueId)
        o.writeInt(e.tracks.size)
        for (t in e.tracks) {
            o.writeInt(t.id); o.writeBoolean(t.manual); o.writeInt(t.start); o.writeInt(t.end); o.writeFloat(t.scale)
            o.path(t.path)
            o.writeInt(t.keys.size)
            for ((k, v) in t.keys) {
                o.writeInt(k)
                o.writeInt(v.size)
                v.forEach(o::writeFloat)
            }
        }
        o.writeInt(e.cues.size)
        for (c in e.cues) {
            o.writeInt(c.id); o.writeLong(c.startMs); o.writeLong(c.endMs)
            o.str(c.he); o.str(c.en); o.writeInt(c.group); o.str(c.src); o.writeInt(c.speaker)
            val ws = c.words
            if (ws == null) o.writeInt(-1) else {
                o.writeInt(ws.size)
                for (w in ws) { o.writeLong(w.startMs); o.writeLong(w.endMs) }
            }
        }
        o.writeInt(e.bleeps.size)
        for (b in e.bleeps) { o.writeLong(b[0]); o.writeLong(b[1]) }
        o.writeInt(e.sfx.size)
        for (c in e.sfx) { o.writeInt(c.id); o.str(c.sound); o.writeLong(c.startMs); o.writeLong(c.durMs); o.writeFloat(c.gain) }
        o.writeInt(e.removed.size)
        for (r in e.removed) { o.writeLong(r[0]); o.writeLong(r[1]) }
        // Blurs the user turned off (by id).
        val off = e.tracks.filter { it.off }
        o.writeInt(off.size)
        for (t in off) o.writeInt(t.id)
        // The masked text each translation was made from (cue id, text); a cue without one has its translation hidden over bleeped words.
        val trs = e.cues.filter { it.trm != null }
        o.writeInt(trs.size)
        for (c in trs) { o.writeInt(c.id); o.str(c.trm!!) }
    }

    fun readEdits(f: File): EditState = input(f).use { i ->
        val version = i.readInt()
        require(version in 1..EDITS_VERSION) { "unknown project version" }
        val nextId = i.readInt()
        val nextCueId = i.readInt()
        val tracks = List(i.readInt()) {
            val id = i.readInt(); val manual = i.readBoolean(); val start = i.readInt(); val end = i.readInt(); val scale = i.readFloat()
            val path = i.path()
            val keys = TreeMap<Int, FloatArray>()
            repeat(i.readInt()) {
                val k = i.readInt()
                keys[k] = FloatArray(i.readInt()) { i.readFloat() }
            }
            EditTrack(id, manual, path, start, end, scale, keys)
        }
        val cues = List(i.readInt()) {
            val id = i.readInt(); val a = i.readLong(); val b = i.readLong()
            val he = i.str(); val en = i.str(); val group = i.readInt(); val src = i.str(); val speaker = i.readInt()
            val n = i.readInt()
            val words = if (n < 0) null else List(n) { TimedWord(i.readLong(), i.readLong()) }
            Cue(id, a, b, he, en, group, src, words, speaker)
        }
        val bleeps = List(i.readInt()) { longArrayOf(i.readLong(), i.readLong()) }
        val sfx = if (version >= 2) List(i.readInt()) { SfxClip(i.readInt(), i.str(), i.readLong(), i.readLong(), i.readFloat()) } else emptyList()
        val removed = if (version >= 3) List(i.readInt()) { longArrayOf(i.readLong(), i.readLong()) } else emptyList()
        if (version >= 4) {
            val off = HashSet<Int>()
            repeat(i.readInt()) { off += i.readInt() }
            for (t in tracks) t.off = t.id in off
        }
        if (version >= 5) {
            val trs = HashMap<Int, String>()
            repeat(i.readInt()) { val id = i.readInt(); trs[id] = i.str() }
            for (c in cues) c.trm = trs[c.id]
        }
        EditState(tracks, cues, bleeps, nextId, nextCueId, sfx, removed)
    }
}
