package com.elad.faceblur.prefs

import com.elad.faceblur.gen.RememberedKeys

/**
 * Remembered choices ("Remember my choice" in a dialog): key -> value, over a small [Store] (the app's
 * SharedPreferences, see [AppPrefs]). No value for a key means "ask each time", the default of every key.
 * Which keys exist and which values they allow is the registry, shared/prefs/choices.json, generated
 * into [RememberedKeys]; a stored value that isn't allowed (an older or newer version's) is ignored, as if never set.
 * Pure Kotlin, so it is tested on the JVM.
 */
class RememberedChoices(private val store: Store, private val registry: List<RememberedKeys.Key> = RememberedKeys.ALL) {
    /** Where the values live: one string per key; null removes it. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    /** A remembered key with its value, for the settings list. */
    class Entry(val key: RememberedKeys.Key, val value: RememberedKeys.Value)

    private fun slot(id: String) = "choice_$id"

    /** The remembered value of [id], or null: ask. */
    fun get(id: String): String? {
        val key = registry.firstOrNull { it.id == id } ?: return null
        val v = store.get(slot(id)) ?: return null
        return key.values.firstOrNull { it.id == v }?.id
    }

    /** Remembers [value] for [id]; false (nothing stored) when the key or the value is not in the registry. */
    fun set(id: String, value: String): Boolean {
        val key = registry.firstOrNull { it.id == id } ?: return false
        if (key.values.none { it.id == value }) return false
        store.put(slot(id), value)
        return true
    }

    /** Back to "ask each time". */
    fun clear(id: String) = store.put(slot(id), null)

    fun clearAll() = registry.forEach { clear(it.id) }

    /** The keys that have a remembered value, in the registry's order. */
    fun remembered(): List<Entry> = registry.mapNotNull { k ->
        val v = get(k.id) ?: return@mapNotNull null
        Entry(k, k.values.first { it.id == v })
    }
}
