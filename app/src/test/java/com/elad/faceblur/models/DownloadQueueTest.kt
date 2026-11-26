package com.elad.faceblur.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The download queue: the user's answers per pack, and a cancelled download ending late. */
class DownloadQueueTest {
    private val mb = 1_000_000L
    private val rule = NetworkRule(askOverMb = 50)
    private val sizes = mapOf(PackId.FACES to 19 * mb, PackId.PEOPLE to 16 * mb, PackId.SPEAKERS to 13 * mb, PackId.SPEECH_HE to 574 * mb)
    private fun plan(q: DownloadQueue, net: Net) = q.plan(rule, net) { sizes.getValue(it) }

    /**
     * Asked about faces + people + speakers + Hebrew speech together (622 MB, over the limit) and
     * answered "Wait for Wi-Fi": none starts on mobile data, the small ones neither.
     */
    @Test
    fun waitForWifiHoldsEveryPackAskedAbout() {
        val q = DownloadQueue()
        val asked = listOf(PackId.FACES, PackId.PEOPLE, PackId.SPEAKERS, PackId.SPEECH_HE)
        q.add(asked, waitForWifi = true)
        val p = plan(q, Net.METERED)
        assertNull("nothing starts on mobile data", p.start)
        for (id in asked) assertEquals(id.key, NetDecision.WAIT_WIFI, p.decisions[id])
        // Wi-Fi: the first one starts.
        assertEquals(PackId.FACES, plan(q, Net.UNMETERED).start)
    }

    @Test
    fun downloadNowLetsOneGo() {
        val q = DownloadQueue()
        q.add(listOf(PackId.FACES, PackId.PEOPLE), waitForWifi = true)
        q.downloadNow(PackId.PEOPLE)
        val p = plan(q, Net.METERED)
        assertEquals(PackId.PEOPLE, p.start)
        assertEquals(NetDecision.WAIT_WIFI, p.decisions[PackId.FACES])
    }

    @Test
    fun withoutAQuestionSmallPacksGoOnMobileData() {
        val q = DownloadQueue()
        q.add(listOf(PackId.FACES))
        assertEquals(PackId.FACES, plan(q, Net.METERED).start)
        // A big one queued on Wi-Fi waits for Wi-Fi again if Wi-Fi drops (no question pops up).
        val big = DownloadQueue().apply { add(listOf(PackId.SPEECH_HE)) }
        assertNull(plan(big, Net.METERED).start)
        assertEquals(NetDecision.WAIT_WIFI, plan(big, Net.METERED).decisions[PackId.SPEECH_HE])
    }

    @Test
    fun answeredDownloadNowGoesOnMobileData() {
        val q = DownloadQueue()
        q.add(listOf(PackId.SPEECH_HE), mobile = true)
        assertEquals(PackId.SPEECH_HE, plan(q, Net.METERED).start)
    }

    @Test
    fun aRunningDownloadPausesWhenItMayNotGoOn() {
        val q = DownloadQueue()
        q.add(listOf(PackId.FACES), waitForWifi = true)
        val t = q.started(plan(q, Net.UNMETERED).start!!)
        val p = plan(q, Net.METERED)
        assertTrue("paused on mobile data", p.pause)
        assertFalse("paused, it stays queued", q.ended(PackId.FACES, t))
        assertTrue(PackId.FACES in q)
        assertNull(q.active)
        assertEquals(PackId.FACES, plan(q, Net.UNMETERED).start)
    }

    @Test
    fun doneLeavesTheQueue() {
        val q = DownloadQueue()
        q.add(listOf(PackId.FACES, PackId.PEOPLE))
        val t = q.started(PackId.FACES)
        assertNull("one at a time", plan(q, Net.UNMETERED).start)
        assertTrue(q.ended(PackId.FACES, t))
        assertFalse(PackId.FACES in q)
        assertEquals(PackId.PEOPLE, plan(q, Net.UNMETERED).start)
    }

    /** Cancelled, asked for again, and only then the old download ends: the new request stays. */
    @Test
    fun aReTapAfterCancelIsKept() {
        val q = DownloadQueue()
        q.add(listOf(PackId.FACES))
        val old = q.started(PackId.FACES)
        assertTrue("it was running", q.cancel(PackId.FACES))
        assertFalse(PackId.FACES in q)
        q.add(listOf(PackId.FACES))
        assertNull("not started again before the old one ends", plan(q, Net.UNMETERED).start)
        assertFalse(q.ended(PackId.FACES, old))
        assertTrue("the new request is still queued", PackId.FACES in q)
        val t = q.started(plan(q, Net.UNMETERED).start!!)
        assertTrue(q.ended(PackId.FACES, t))
    }

    @Test
    fun cancelClearsTheAnswers() {
        val q = DownloadQueue()
        q.add(listOf(PackId.FACES), mobile = true)
        q.cancel(PackId.FACES)
        q.add(listOf(PackId.SPEECH_HE))
        q.add(listOf(PackId.FACES))
        // The old "download now" is gone with the cancel: under the limit it goes anyway, by the rule.
        assertEquals(NetDecision.START, plan(q, Net.METERED).decisions[PackId.FACES])
        assertEquals(NetDecision.WAIT_WIFI, plan(q, Net.METERED).decisions[PackId.SPEECH_HE])
    }
}
