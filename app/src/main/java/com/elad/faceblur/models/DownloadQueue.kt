package com.elad.faceblur.models

/**
 * The queue of model downloads and what the user said about each one, without Android: which
 * pack starts, which waits (for any network, or for Wi-Fi), and which running one must pause.
 * Not thread-safe: [ModelDownloads] calls it under its lock.
 *
 * Each started download gets a token: a download the user cancelled (and may have asked for
 * again since) ends without touching the queue entry of the new request.
 */
class DownloadQueue {
    private val queue = ArrayList<PackId>()
    /** The user said these may use mobile data ("Download now"). */
    private val mobileOk = HashSet<PackId>()
    /** The user said these wait for Wi-Fi ("Wait for Wi-Fi"), whatever their size. */
    private val wifiWait = HashSet<PackId>()
    /** Stopped by a network change, not by the user: they stay queued. */
    private val paused = HashSet<PackId>()
    /** Cancelled while running and not ended yet: not started again until they end. */
    private val ending = HashSet<PackId>()
    private var tokens = 0L

    /** The pack downloading now, if any. */
    var active: PackId? = null
        private set
    private var activeToken = 0L

    val ids: List<PackId> get() = queue.toList()

    operator fun contains(id: PackId) = id in queue

    val isEmpty: Boolean get() = queue.isEmpty()

    /**
     * Queues [ids] (in order, once). [waitForWifi]: the user answered "Wait for Wi-Fi";
     * [mobile]: "Download now" on mobile data.
     */
    fun add(ids: List<PackId>, waitForWifi: Boolean = false, mobile: Boolean = false) {
        for (id in ids) {
            if (id !in queue) queue += id
            if (mobile) {
                mobileOk += id
                wifiWait -= id
            } else if (waitForWifi) {
                wifiWait += id
                mobileOk -= id
            }
        }
    }

    /** "Download now" on a download that waits for Wi-Fi. */
    fun downloadNow(id: PackId) {
        mobileOk += id
        wifiWait -= id
    }

    /** The user cancelled [id]: off the queue; if it runs, it ends (true: it was running). */
    fun cancel(id: PackId): Boolean {
        queue.remove(id)
        mobileOk.remove(id)
        wifiWait.remove(id)
        paused.remove(id)
        if (active != id) return false
        active = null
        ending += id
        return true
    }

    fun decide(id: PackId, rule: NetworkRule, net: Net, bytes: Long): NetDecision =
        NetworkPolicy.decide(rule, net, bytes, mobileOk = id in mobileOk, asking = false, wifiWait = id in wifiWait)

    /**
     * What to do now. [decisions]: each queued pack's (the screens mark the waiting ones);
     * [pause]: the running one may not go on (it waits, its partial file kept); [start]: the
     * next to start, if none runs.
     */
    class Plan(val decisions: Map<PackId, NetDecision>, val pause: Boolean, val start: PackId?)

    fun plan(rule: NetworkRule, net: Net, bytesOf: (PackId) -> Long): Plan {
        val decisions = queue.associateWith { decide(it, rule, net, bytesOf(it)) }
        val a = active
        if (a != null) {
            val d = decisions[a] ?: NetDecision.START
            if (d != NetDecision.START && a !in paused) {
                paused += a
                return Plan(decisions, pause = true, start = null)
            }
            return Plan(decisions, pause = false, start = null)
        }
        val next = queue.firstOrNull { decisions[it] == NetDecision.START && it !in ending }
        return Plan(decisions, pause = false, start = next)
    }

    /** [id] starts: its token, to give back to [ended]. */
    fun started(id: PackId): Long {
        active = id
        paused -= id
        activeToken = ++tokens
        return activeToken
    }

    /**
     * The download with [token] of [id] ended (done, failed, cancelled or paused). True when the
     * pack leaves the queue (done or failed); false when it waits (paused) or was cancelled (its
     * entry, if the user asked again, belongs to the new request).
     */
    fun ended(id: PackId, token: Long): Boolean {
        ending -= id
        if (active != id || activeToken != token) return false
        active = null
        if (paused.remove(id)) return false
        queue.remove(id)
        mobileOk.remove(id)
        wifiWait.remove(id)
        return true
    }
}
