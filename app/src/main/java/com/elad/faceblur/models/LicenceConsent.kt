package com.elad.faceblur.models

/** Where acceptances are kept: pack id to the licence version accepted (plain values, so tests need no Android). */
interface AcceptanceStorage {
    /** The accepted version of the pack, or 0. */
    fun get(pack: String): Int

    fun put(pack: String, version: Int)

    fun remove(pack: String)
}

/**
 * The user's acceptance of a model's licence terms, for packs whose registry entry says
 * `requiresAcceptance` (terms with restrictions beyond MIT / Apache-2.0). It is stored per pack
 * and per [Pack.licenceVersion]: new terms (a bumped version) ask again, and deleting the pack
 * ([ModelStore.delete]) clears it, so a later download asks again too. A pack that needs no
 * acceptance always counts as accepted. Nothing downloads before this says yes
 * ([ModelDownloads.request]).
 */
class LicenceConsent(private val storage: AcceptanceStorage) {

    /** The pack can be downloaded as far as its licence goes. */
    fun isAccepted(p: Pack): Boolean = !p.requiresAcceptance || storage.get(p.id.key) == p.licenceVersion

    /** Of [packs], those whose terms still have to be accepted, in the order given. */
    fun needed(packs: Collection<Pack>): List<Pack> = packs.filter { !isAccepted(it) }

    /** Records the acceptance of [packs] (at their current licence version). */
    fun accept(packs: Collection<Pack>) {
        for (p in packs) if (p.requiresAcceptance) storage.put(p.id.key, p.licenceVersion)
    }

    /** Forgets the pack's acceptance (it was deleted). */
    fun clear(id: PackId) = storage.remove(id.key)
}

/**
 * The packs a tap asked for that wait for the user's acceptance, with what else the tap asked
 * for: when accepted, [ids] (and translation's pack, if [translation]) are requested as they were.
 * [needed] is what the dialog lists (one combined consent for "Download all").
 */
data class ConsentAsk(val needed: List<Pack>, val ids: List<PackId>, val translation: Boolean)

/** How the licence screens write a model's notice: the copyright line, then the full terms. */
object LicenceText {
    /** The notice shown above the terms (copyright, licence name, and the change note when there is one). */
    fun header(l: LicenceInfo): String = buildString {
        if (l.copyright.isNotBlank()) append(l.copyright).append('\n')
        if (l.licence.isNotBlank()) append(l.licence)
        if (l.note.isNotBlank()) append('\n').append(l.note)
    }.trim()

    /** The header and the bundled terms [terms] as one text (what "full licence" shows and what is copied). */
    fun full(l: LicenceInfo, terms: String): String = header(l) + (if (terms.isBlank()) "" else "\n\n" + terms.trim())

    /**
     * The licences of a pack's files, one entry for files with the same terms and copyright (the
     * faces pack's three RetinaFace files are one): the first file's title, the notes joined, in the files' order.
     */
    fun group(files: List<ModelFile>): List<LicenceInfo> {
        val out = LinkedHashMap<Triple<String, String, String>, LicenceInfo>()
        for (f in files) {
            val l = f.licence
            if (l.licence.isBlank()) continue
            val key = Triple(l.licence, l.copyright, l.licenceFile)
            val prev = out[key]
            out[key] = if (prev == null) l else prev.copy(
                note = joinDistinct(prev.note, l.note, " "),
                requiresAcceptance = prev.requiresAcceptance || l.requiresAcceptance,
                unverified = joinDistinct(prev.unverified, l.unverified, " "),
            )
        }
        return out.values.toList()
    }

    private fun joinDistinct(a: String, b: String, sep: String): String = when {
        b.isBlank() || b == a -> a
        a.isBlank() -> b
        else -> a + sep + b
    }
}
