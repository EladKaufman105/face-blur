package com.elad.faceblur.template

import org.json.JSONObject
import java.io.File

/**
 * The template files under [root], with no Android in it (so it can be tested): the user's
 * templates are `<id>.json`; the user's changes to a built-in look (an override, same id) are in
 * `builtin/<id>.json`. A background picture a template uses sits beside its file as `<id>.bg.jpg`.
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
    }

    /** Where the background picture of template [id] is kept. */
    fun imageFile(id: String): File =
        if (BuiltInTemplates.byId(id) != null) File(overrideDir(), "$id.bg.jpg") else File(userDir(), "$id.bg.jpg")

    /** Deletes the pictures of user templates that are gone (only [ids] are kept). */
    fun pruneImages(ids: Set<String>) {
        userDir().listFiles { f -> f.name.endsWith(".bg.jpg") }?.forEach { f ->
            if (f.name.removeSuffix(".bg.jpg") !in ids) f.delete()
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
