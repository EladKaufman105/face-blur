package com.elad.faceblur.models

/**
 * When a model may download, the user's choice (Settings › Models, and the first-launch sheet):
 * on Wi-Fi only, or on mobile data too, asking first for a download larger than [askOverMb].
 */
data class NetworkRule(val wifiOnly: Boolean = false, val askOverMb: Int = DEFAULT_MB) {
    val askOverBytes: Long get() = askOverMb.toLong() * 1_000_000L

    companion object {
        /** The sizes the user picks from (0: always ask on mobile data). */
        val CHOICES_MB = listOf(0, 25, 50, 100, 250)
        const val DEFAULT_MB = 50
    }
}

/** The network now: none, a metered one (mobile data, a hotspot), or an unmetered one (Wi-Fi). */
enum class Net { NONE, METERED, UNMETERED }

/** What to do with a download, now. */
enum class NetDecision {
    /** Start it. */
    START,
    /** Ask first ("Download on mobile data?"): [START] or wait for Wi-Fi. */
    ASK,
    /** Wait for Wi-Fi (an unmetered network). */
    WAIT_WIFI,
    /** Wait for any connection. */
    WAIT_NETWORK,
}

object NetworkPolicy {
    /**
     * The decision for a download of [bytes] on [net] under [rule].
     * @param mobileOk the user already said yes to mobile data for this download ("Download now").
     * @param asking the user is there to be asked (a tap on a download button); a download that
     *   was waiting and isn't allowed now waits for Wi-Fi instead of popping a question.
     */
    fun decide(rule: NetworkRule, net: Net, bytes: Long, mobileOk: Boolean, asking: Boolean): NetDecision = when {
        net == Net.NONE -> NetDecision.WAIT_NETWORK
        net == Net.UNMETERED -> NetDecision.START
        mobileOk -> NetDecision.START
        rule.wifiOnly -> NetDecision.WAIT_WIFI
        bytes <= rule.askOverBytes && rule.askOverMb > 0 -> NetDecision.START
        asking -> NetDecision.ASK
        else -> NetDecision.WAIT_WIFI
    }
}
