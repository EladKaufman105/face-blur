package com.elad.faceblur.template

import org.json.JSONObject
import java.io.File

/**
 * The template files under [root], with no Android in it (so it can be tested): the user's
 * templates are `<id>.json`; the user's changes to a built-in look (an override, same id) are in
 * `builtin/<id>.json`. A background picture a template uses sits beside its file as `<id>.bg.jpg`, and a logo for its
 * watermark as `<id>.wm.png` (the app's own logo is never touched by a template).
 */
class TemplateFiles(private val root: File) {
    private fun userDir(): File = root.apply { mkdirs() }
    private fun overrideDir(): File = File(root, OVERRIDES).apply { mkdirs() }

    /** The user's templates, newest first. */
    fun readUser(): List<Template> =
        (userDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Template.fromJson(JSONObject(f.readText()))?.to(f.lastModified()) }.getOrNull() }
            .sortedByDescending { it.second }
            .map { it.first }

    fun writeUser(tpl: Template) = write(userDir(), tpl.copy(group = TemplateGroup.USER, builtIn = false))

    fun deleteUser(id: String) {
        File(userDir(), "$id.json").delete()
        File(userDir(), "$id.bg.jpg").delete()
        File(userDir(), "$id.wm.png").delete()
    }

    /**
     * The user's versions of the built-in looks, by id. A file of a look that is no longer
     * shipped is ignored, and one that can't be read is skipped (the shipped look is used).
     */
    fun readOverrides(): Map<String, Template> {
        val out = LinkedHashMap<String, Template>()
        for (f in overrideDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()) {
            val t = runCatching { Template.fromJson(JSONObject(f.readText())) }.getOrNull() ?: continue
            if (BuiltInTemplates.byId(t.id) == null) continue
            out[t.id] = t.copy(group = TemplateGroup.LOOK, builtIn = true)
        }
        return out
    }

    fun writeOverride(tpl: Template) {
        require(BuiltInTemplates.byId(tpl.id) != null) { "not a built-in: ${tpl.id}" }
        write(overrideDir(), tpl.copy(group = TemplateGroup.LOOK, builtIn = true))
    }

    /** Back to the shipped version of the built-in [id]. */
    fun deleteOverride(id: String) {
        File(overrideDir(), "$id.json").delete()
        File(overrideDir(), "$id.bg.jpg").delete()
        File(overrideDir(), "$id.wm.png").delete()
    }

    /** Where the background picture of template [id] is kept. */
    fun imageFile(id: String): File =
        if (BuiltInTemplates.byId(id) != null) File(overrideDir(), "$id.bg.jpg") else File(userDir(), "$id.bg.jpg")

    /** Where the watermark logo of template [id] is kept. */
    fun logoFile(id: String): File =
        if (BuiltInTemplates.byId(id) != null) File(overrideDir(), "$id.wm.png") else File(userDir(), "$id.wm.png")

    /**
     * Puts the logo of template [id] in place when the template is saved. [picked] is the logo the
     * template has now: a staging file (copied in, replacing the old logo), the template's own file
     * (kept) or null (no logo: the old file is removed). Returns the path the template keeps, or null.
     * Discarding the edit never gets here, so the saved logo stays as it was.
     */
    fun commitLogo(id: String, picked: String?): String? {
        val own = logoFile(id)
        if (picked == null) {
            own.delete()
            return null
        }
        val src = File(picked)
        if (src.absolutePath == own.absolutePath) return if (own.exists()) own.path else null
        if (!src.exists()) return if (own.exists()) own.path else null
        own.parentFile?.mkdirs()
        val tmp = File(own.parentFile, own.name + ".tmp")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(own)) {
            own.delete()
            tmp.renameTo(own)
        }
        return own.path
    }

    /** Deletes the pictures and logos of user templates that are gone (only [ids] are kept). */
    fun pruneImages(ids: Set<String>) {
        userDir().listFiles { f -> f.name.endsWith(".bg.jpg") || f.name.endsWith(".wm.png") }?.forEach { f ->
            if (f.name.removeSuffix(".bg.jpg").removeSuffix(".wm.png") !in ids) f.delete()
        }
    }

    private fun write(dir: File, tpl: Template) {
        val f = File(dir, tpl.id + ".json")
        val tmp = File(dir, tpl.id + ".json.tmp")
        tmp.writeText(tpl.toJson().toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val OVERRIDES = "builtin"
    }
}
