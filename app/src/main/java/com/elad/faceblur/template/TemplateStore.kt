package com.elad.faceblur.template

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.i18n.t
import com.elad.faceblur.subs.FontStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The user's templates, one JSON file each in the app's own storage (templates/<id>.json); a
 * background picture a template uses is copied beside it (templates/<id>.bg.jpg). The built-in
 * looks and destinations are in [BuiltInTemplates].
 */
class TemplateStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "templates").apply { mkdirs() }

    /** The user's templates, newest first. Read this from composition to follow changes. */
    var user by mutableStateOf(load())
        private set

    private fun load(): List<Template> =
        (File(context.filesDir, "templates").listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Template.fromJson(JSONObject(f.readText()))?.to(f.lastModified()) }.getOrNull() }
            .sortedByDescending { it.second }
            .map { it.first }

    fun find(id: String): Template? = user.firstOrNull { it.id == id } ?: BuiltInTemplates.byId(id)

    fun newId(): String = "u" + UUID.randomUUID().toString().take(8)

    /** Saves [tpl] as the user's own (replacing the one with its id). */
    fun save(tpl: Template) {
        val own = tpl.copy(group = TemplateGroup.USER, builtIn = false)
        val f = File(dir, own.id + ".json")
        val tmp = File(dir, own.id + ".json.tmp")
        tmp.writeText(own.toJson().toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        user = listOf(own) + user.filter { it.id != own.id }
        pruneImages()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.bg.jpg").delete()
        user = user.filter { it.id != id }
    }

    fun rename(id: String, name: String) {
        val n = name.trim().take(60)
        val old = user.firstOrNull { it.id == id } ?: return
        if (n.isEmpty()) return
        save(old.copy(name = n))
    }

    /** A copy of [id] (a built-in too) as the user's own, named [name]. */
    fun duplicate(id: String, name: String): Template? {
        val src = find(id) ?: return null
        val newId = newId()
        val bg = src.background?.let { withOwnImage(it, newId) }
        val copy = src.copy(id = newId, name = name, group = TemplateGroup.USER, builtIn = false, background = bg)
        save(copy)
        return copy
    }

    /** [b] with its picture copied into the template [id]'s own storage (so deleting the original doesn't break the template). */
    fun withOwnImage(b: com.elad.faceblur.effects.Background, id: String): com.elad.faceblur.effects.Background {
        val path = b.image ?: return b
        val src = File(path)
        if (!src.exists()) return b.copy(image = null)
        val dst = File(dir, "$id.bg.jpg")
        if (src.absolutePath != dst.absolutePath) src.copyTo(dst, overwrite = true)
        return b.copy(image = dst.absolutePath)
    }

    /** Pictures of templates that are gone. */
    private fun pruneImages() {
        val ids = user.map { it.id }.toSet()
        dir.listFiles { f -> f.name.endsWith(".bg.jpg") }?.forEach { f ->
            if (f.name.removeSuffix(".bg.jpg") !in ids) f.delete()
        }
    }

    /**
     * [tpl] made safe to use here: an imported font that is gone falls back to the default font
     * and a background picture that is gone to no picture. [notes] gets a short notice for each.
     */
    fun usable(tpl: Template, notes: MutableList<String>? = null): Template {
        var r = tpl
        val style = r.subs?.style
        if (style?.customFont != null && !FontStore.file(context, style.customFont).exists()) {
            r = r.copy(subs = r.subs!!.copy(style = style.copy(customFont = null)))
            notes?.add(t("הגופן המיובא של התבנית לא נמצא. נעשה שימוש בגופן ברירת המחדל."))
        }
        val bg = r.background
        if (bg != null && bg.fill == BgFill.IMAGE && (bg.image == null || !File(bg.image).exists())) {
            r = r.copy(background = bg.copy(fill = BgFill.NONE, image = null))
            notes?.add(t("תמונת הרקע של התבנית לא נמצאה. הרקע לא ישתנה."))
        }
        return r
    }
}
