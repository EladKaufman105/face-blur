package com.elad.faceblur.models

import org.json.JSONObject

/** A line of the "Open-source licences" screen: a model, a library or a font, and its licence. */
data class LicenceEntry(
    val id: String,
    /** "model", "library" or "font". */
    val kind: String,
    /** Where it is used: "android", "web" or both (shown as is). */
    val used: String,
    val info: LicenceInfo,
)

/**
 * What the "Open-source licences" screen lists: every model file of the registry, then the
 * libraries, fonts and other models in assets/licences/components.json (shared/assets/licences,
 * the same file the web app serves). Texts are bundled in the assets, so it works offline.
 */
object LicenceCatalog {
    /** The components file in the assets. */
    const val COMPONENTS = "licences/components.json"

    private val KINDS = setOf("model", "library", "font")

    fun parseComponents(json: String): List<LicenceEntry> {
        val arr = JSONObject(json).getJSONArray("components")
        val out = ArrayList<LicenceEntry>()
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            require(ids.add(id)) { "component $id twice" }
            val kind = o.getString("kind")
            require(kind in KINDS) { "component $id: bad kind $kind" }
            out += LicenceEntry(
                id, kind, o.optString("used", ""),
                LicenceInfo(
                    title = o.getString("name"),
                    licence = o.getString("licence"),
                    copyright = o.optString("copyright", ""),
                    source = o.optString("source", ""),
                    licenceFile = o.optString("licenceFile", ""),
                    note = o.optString("note", ""),
                    unverified = o.optString("unverified", ""),
                ),
            )
        }
        return out
    }

    /** The registry's models, one entry per set of files with the same terms and copyright (see [LicenceText.group]). */
    fun models(registry: ModelRegistry): List<LicenceEntry> =
        registry.packs.flatMap { p -> LicenceText.group(p.files).mapIndexed { i, l -> LicenceEntry("${p.id.key}-$i", "model", "", l) } }
}
