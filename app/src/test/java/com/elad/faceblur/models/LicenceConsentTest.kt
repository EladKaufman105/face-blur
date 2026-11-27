package com.elad.faceblur.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The acceptance of a model's licence terms: per pack and licence version, cleared with the pack. */
class LicenceConsentTest {

    private class Memory : AcceptanceStorage {
        val m = HashMap<String, Int>()
        override fun get(pack: String) = m[pack] ?: 0
        override fun put(pack: String, version: Int) { m[pack] = version }
        override fun remove(pack: String) { m.remove(pack) }
    }

    private fun lic(restricted: Boolean) = LicenceInfo("T", "Non-commercial research", "(c) X", "https://example.com", "licences/MIT.txt", restricted)
    private fun file(name: String, restricted: Boolean) = ModelFile(name, "https://example.com/$name", 10, "a".repeat(64), licence = lic(restricted))
    private fun pack(id: PackId, restricted: Boolean, version: Int = 1) = Pack(id, 1, 1, false, listOf(file("${id.key}.bin", restricted)), licenceVersion = version)

    @Test
    fun aPackWithoutRestrictionsNeedsNothing() {
        val c = LicenceConsent(Memory())
        assertTrue(c.isAccepted(pack(PackId.FACES, false)))
        assertTrue(c.needed(listOf(pack(PackId.FACES, false))).isEmpty())
    }

    @Test
    fun aRestrictedPackAsksUntilAccepted() {
        val c = LicenceConsent(Memory())
        val p = pack(PackId.SELFIE, true)
        assertFalse(c.isAccepted(p))
        assertEquals(listOf(p), c.needed(listOf(p)))
        c.accept(listOf(p))
        assertTrue(c.isAccepted(p))
        assertTrue(c.needed(listOf(p)).isEmpty())
    }

    @Test
    fun newTermsAskAgain() {
        val c = LicenceConsent(Memory())
        c.accept(listOf(pack(PackId.SELFIE, true, version = 1)))
        assertFalse(c.isAccepted(pack(PackId.SELFIE, true, version = 2)))
        // An acceptance never counts for another pack.
        assertFalse(c.isAccepted(pack(PackId.PEOPLE, true, version = 1)))
    }

    @Test
    fun deletingThePackClearsIt() {
        val s = Memory()
        val c = LicenceConsent(s)
        val p = pack(PackId.SELFIE, true)
        c.accept(listOf(p))
        c.clear(PackId.SELFIE)
        assertFalse(c.isAccepted(p))
        assertTrue(s.m.isEmpty())
    }

    /** "Download all": one consent lists exactly the packs that need it, in order, and accepting all records only those. */
    @Test
    fun oneCombinedConsentForDownloadAll() {
        val s = Memory()
        val c = LicenceConsent(s)
        val a = pack(PackId.FACES, false)
        val b = pack(PackId.SELFIE, true)
        val d = pack(PackId.SPEAKERS, true)
        val ask = c.needed(listOf(a, b, d))
        assertEquals(listOf(b, d), ask)
        c.accept(ask)
        assertEquals(setOf("selfie", "speakers"), s.m.keys)
        assertTrue(c.needed(listOf(a, b, d)).isEmpty())
    }

    @Test
    fun textIsCopyrightThenLicenceThenTerms() {
        val l = LicenceInfo("T", "MIT", "Copyright (c) 2024 X", "https://example.com", "licences/MIT.txt", false, "Changed.")
        assertEquals("Copyright (c) 2024 X\nMIT\nChanged.", LicenceText.header(l))
        assertEquals("Copyright (c) 2024 X\nMIT\nChanged.\n\nTerms.", LicenceText.full(l, "  Terms.\n"))
        assertEquals("Copyright (c) 2024 X\nMIT\nChanged.", LicenceText.full(l, ""))
    }
}
