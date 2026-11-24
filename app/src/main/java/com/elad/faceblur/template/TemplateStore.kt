package com.elad.faceblur.template

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.i18n.t
import com.elad.faceblur.subs.FontStore
import java.io.File
import java.util.UUID

/**
 * The user's templates, one JSON file each in the app's own storage (templates/<id>.json); a
 * background picture a template uses is copied beside it (templates/<id>.bg.jpg). The built-in
 * looks are in [BuiltInTemplates]; the user's changes to one are saved as an override beside the
 * user's own (see [TemplateFiles]) and survive app updates until the look is restored.
 */
class TemplateStore(private val context: Context) {
    private val files = TemplateFiles(File(context.filesDir, "templates"))

    /** The user's templates, newest first. Read this from composition to follow changes. */
    var user by mutableStateOf(files.readUser())
        private set

    /** The user's own versions of built-in looks (same id), which replace the shipped ones everywhere. */
    var overrides by mutableStateOf(files.readOverrides())
        private set

    /** The built-in looks as they are now: the user's version where there is one, else the shipped one. */
    val builtIns: List<Template> get() = BuiltInTemplates.all.map { overrides[it.id] ?: it }

    fun isOverridden(id: String): Boolean = id in overrides

    fun find(id: String): Template? = user.firstOrNull { it.id == id } ?: overrides[id] ?: BuiltInTemplates.byId(id)

    fun newId(): String = "u" + UUID.randomUUID().toString().take(8)

    /** Saves [tpl] as the user's own (replacing the one with its id). */
    fun save(tpl: Template) {
        val own = tpl.copy(group = TemplateGroup.USER, builtIn = false)
        files.writeUser(own)
        user = listOf(own) + user.filter { it.id != own.id }
        files.pruneImages(user.map { it.id }.toSet())
    }

    /** Saves the user's version of a built-in look (it keeps the look's id and is a built-in still). */
    fun saveBuiltIn(tpl: Template) {
        if (BuiltInTemplates.byId(tpl.id) == null) return
        files.writeOverride(tpl)
        overrides = overrides + (tpl.id to tpl.copy(group = TemplateGroup.LOOK, builtIn = true))
    }

    /** Brings back the shipped version of the built-in look [id]. */
    fun restoreBuiltIn(id: String) {
        files.deleteOverride(id)
        overrides = overrides - id
    }

    fun delete(id: String) {
        // A built-in can't be deleted.
        if (BuiltInTemplates.byId(id) != null) return
        files.deleteUser(id)
        user = user.filter { it.id != id }
    }

    fun rename(id: String, name: String) {
        val n = name.trim().take(60)
        val old = user.firstOrNull { it.id == id } ?: return
        if (n.isEmpty()) return
        save(old.copy(name = n))
    }

    /** A copy of [id] (a built-in too, as the user has it) as the user's own, named [name]. */
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
        val dst = files.imageFile(id)
        if (src.absolutePath != dst.absolutePath) src.copyTo(dst, overwrite = true)
        return b.copy(image = dst.absolutePath)
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
