package com.elad.faceblur.prefs

import com.elad.faceblur.gen.RememberedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remembered choices' logic over an in-memory store (the same rules as the web's tests/choices.cjs). */
class RememberedChoicesTest {
    private class MapStore : RememberedChoices.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    private val store = MapStore()
    private val choices = RememberedChoices(store)

    @Test
    fun theRegistryHasLabelsInBothLanguagesAndTwoValuesPerKey() {
        assertTrue(RememberedKeys.ALL.isNotEmpty())
        for (k in RememberedKeys.ALL) {
            assertTrue(k.id, k.he.isNotBlank() && k.en.isNotBlank() && k.values.size >= 2)
            for (v in k.values) assertTrue(k.id + "." + v.id, v.he.isNotBlank() && v.en.isNotBlank())
        }
        assertEquals(RememberedKeys.ALL.size, RememberedKeys.ALL.map { it.id }.toSet().size)
        assertEquals(listOf("add", "replace"), RememberedKeys.ALL.first { it.id == "rerun.merge" }.values.map { it.id })
    }

    @Test
    fun nothingIsRememberedByDefaultSoEveryKeyAsks() {
        for (k in RememberedKeys.ALL) assertNull(choices.get(k.id))
        assertTrue(choices.remembered().isEmpty())
    }

    @Test
    fun aRememberedValueComesBackAndClearGoesBackToAsking() {
        assertTrue(choices.set("rerun.merge", "replace"))
        assertEquals("replace", choices.get("rerun.merge"))
        val l = choices.remembered()
        assertEquals(1, l.size)
        assertEquals("rerun.merge", l[0].key.id)
        assertEquals("replace", l[0].value.id)
        choices.clear("rerun.merge")
        assertNull(choices.get("rerun.merge"))
        assertTrue(choices.remembered().isEmpty())
    }

    @Test
    fun clearAllClearsEveryKey() {
        choices.set("rerun.merge", "add")
        choices.clearAll()
        assertTrue(choices.remembered().isEmpty())
        assertTrue(store.m.isEmpty())
    }

    @Test
    fun aValueOrKeyOutsideTheRegistryIsNeverRemembered() {
        assertFalse(choices.set("rerun.merge", "bogus"))
        assertFalse(choices.set("no.such.key", "add"))
        assertTrue(store.m.isEmpty())
        // A stored value the registry doesn't allow (another version's) is read as "ask".
        store.m["choice_rerun.merge"] = "something else"
        assertNull(choices.get("rerun.merge"))
        assertTrue(choices.remembered().isEmpty())
    }
}
