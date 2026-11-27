package com.elad.faceblur.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** The user's model choices that persist: the network rule, the first-launch sheet seen, notifications asked. */
class ModelPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("models", Context.MODE_PRIVATE)

    var rule by mutableStateOf(
        NetworkRule(
            wifiOnly = sp.getBoolean("wifi_only", false),
            askOverMb = sp.getInt("ask_over_mb", NetworkRule.DEFAULT_MB),
        ),
    )
        private set

    /** The first-launch sheet ("ONIT's models") was shown and closed. */
    var introSeen by mutableStateOf(sp.getBoolean("intro_seen", false))
        private set

    /** Notifications were asked for once (for the download progress); never asked again. */
    val notificationsAsked: Boolean get() = sp.getBoolean("notifications_asked", false)

    fun updateRule(r: NetworkRule) {
        rule = r
        sp.edit().putBoolean("wifi_only", r.wifiOnly).putInt("ask_over_mb", r.askOverMb).apply()
    }

    fun markIntroSeen() {
        introSeen = true
        sp.edit().putBoolean("intro_seen", true).apply()
    }

    fun markNotificationsAsked() {
        sp.edit().putBoolean("notifications_asked", true).apply()
    }
}

/**
 * "Download on mobile data?": the packs a tap asked for, their size, and whether translation's
 * pack (ML Kit) goes with them.
 */
data class MeteredQuestion(val packs: List<PackId>, val bytes: Long, val translation: Boolean = false)

/**
 * The downloads of model packs: one at a time, in the order asked, each started only when the
 * network rule allows ([NetworkPolicy], [DownloadQueue]): waiting ones start when the network
 * changes. Nothing downloads without a tap. While one runs, [ModelDownloadService] keeps the app
 * alive and shows the progress in a notification.
 */
class ModelDownloads private constructor(context: Context) {
    private val app = context.applicationContext
    val store = ModelStore.get(app)
    val prefs = ModelPrefs(app)
    private val downloader = Downloader()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val queue = DownloadQueue()
    private var activeJob: kotlinx.coroutines.Job? = null

    /** The network now (for the screens: "No internet connection"). */
    var net by mutableStateOf(Net.NONE)
        private set

    /** A question for the screen to ask ("Download on mobile data?"), or null. */
    var question by mutableStateOf<MeteredQuestion?>(null)
        private set

    /**
     * Packs a tap asked for whose licence terms aren't accepted yet: the screen shows the consent
     * ([LicenceConsentHost]) and answers with [acceptConsent] or [declineConsent]. Nothing of the
     * tap is queued before that.
     */
    var consentAsk by mutableStateOf<ConsentAsk?>(null)
        private set

    /** The last pack that became ready (the screen says so once), or null. */
    var justReady by mutableStateOf<PackId?>(null)

    /**
     * Set when the first download starts on Android 13+ without permission to notify: the screen
     * asks once (the download goes on whatever the answer). The screen sets it back.
     */
    var askNotifications by mutableStateOf(false)

    init {
        watchNetwork()
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        net = netOf(cm.getNetworkCapabilities(cm.activeNetwork))
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    changed(netOf(caps))
                }

                override fun onLost(network: Network) {
                    changed(Net.NONE)
                }
            })
        } catch (e: RuntimeException) {
            Log.w("ONIT", "no network callback", e)
        }
    }

    private fun netOf(caps: NetworkCapabilities?): Net = when {
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Net.NONE
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> Net.UNMETERED
        else -> Net.METERED
    }

    /** Translation's pack was asked for with no network: it starts when one comes. */
    @Volatile private var translationOnNetwork = false

    private fun changed(n: Net) {
        if (n == net) return
        net = n
        if (n != Net.NONE && translationOnNetwork) {
            translationOnNetwork = false
            val r = prefs.rule
            TranslationPacks.download(wifiOnly = r.wifiOnly || (n == Net.METERED && TranslationPacks.APPROX_BYTES > r.askOverBytes))
        }
        pump()
    }

    /** The user changed the rule: waiting downloads are looked at again. */
    fun updateRule(r: NetworkRule) {
        prefs.updateRule(r)
        pump()
    }

    private fun bytesLeft(id: PackId): Long {
        val p = store.registry[id]
        return p.files.sumOf { f ->
            val dest = File(store.dir, f.name)
            // An old version's file of the same size may still differ: counted whole then.
            val size = f.expectedSize
            if (f.pinned && dest.length() == f.size && store.status(id).installed != Installed.UPDATE_AVAILABLE) 0L
            else size - File(store.dir, f.name + ".part").length().coerceIn(0L, size)
        }
    }

    /**
     * A tap on a download button: queues [ids] (those that can be downloaded), and translation's
     * pack with them if [translation]; or asks first ([question]) when they are too big for mobile
     * data by the user's rule. Nothing starts before the answer.
     * @return what happens now ([NetDecision.WAIT_NETWORK]: the screen says it starts when online).
     */
    fun request(ids: Collection<PackId>, translation: Boolean = false): NetDecision {
        val want = ids.distinct().filter { id ->
            val s = store.status(id)
            s.installed != Installed.PENDING && s.installed != Installed.READY && !s.busy && !s.waiting
        }
        val withTranslation = translation && TranslationPacks.state != TranslationPacks.State.READY &&
            TranslationPacks.state != TranslationPacks.State.DOWNLOADING && TranslationPacks.state != TranslationPacks.State.WAITING_WIFI
        if (want.isEmpty() && !withTranslation) return NetDecision.START
        // Terms that need accepting come first, for every pack of the tap in one question. START is
        // returned while the consent is pending (nothing is queued, so no "waits for a connection"
        // toast); acceptConsent() asks again and returns the real decision.
        val unaccepted = store.consent.needed(want.map { store.registry[it] })
        if (unaccepted.isNotEmpty()) {
            consentAsk = ConsentAsk(unaccepted, want, withTranslation)
            return NetDecision.START
        }
        val bytes = want.sumOf { bytesLeft(it) } + (if (withTranslation) TranslationPacks.APPROX_BYTES else 0L)
        val d = NetworkPolicy.decide(prefs.rule, net, bytes, mobileOk = false, asking = true)
        if (d == NetDecision.ASK) {
            question = MeteredQuestion(want, bytes, withTranslation)
            return d
        }
        // ML Kit waits for Wi-Fi by itself when the rule says so; with no network at all it would
        // fail at once, so it starts when a network comes ([changed]).
        if (withTranslation) {
            if (d == NetDecision.WAIT_NETWORK) translationOnNetwork = true
            else TranslationPacks.download(wifiOnly = prefs.rule.wifiOnly || d == NetDecision.WAIT_WIFI)
        }
        enqueue(want, waitForWifi = false, mobile = false)
        return d
    }

    /**
     * "Accept & download": the terms are recorded, and the tap goes on as it was (the network rule
     * still applies).
     * @return what [request] decides now ([NetDecision.WAIT_NETWORK]: the screen says it starts when online).
     */
    fun acceptConsent(): NetDecision {
        val a = consentAsk ?: return NetDecision.START
        consentAsk = null
        store.consent.accept(a.needed)
        return request(a.ids, a.translation)
    }

    /** The consent was cancelled: nothing is queued and nothing is recorded. */
    fun declineConsent() {
        consentAsk = null
    }

    /**
     * The answer to [question]: download now on mobile data, or wait for Wi-Fi. "Wait" holds every
     * pack it asked about until Wi-Fi (small ones too), or until "Download now" on one of them.
     */
    fun answer(downloadNow: Boolean) {
        val q = question ?: return
        question = null
        if (q.translation) TranslationPacks.download(wifiOnly = !downloadNow)
        enqueue(q.packs, waitForWifi = !downloadNow, mobile = downloadNow)
    }

    /** The question closed without an answer: nothing is queued. */
    fun dismissQuestion() {
        question = null
    }

    /** "Download now" on a download that waits for Wi-Fi. */
    fun downloadNow(id: PackId) {
        synchronized(lock) { queue.downloadNow(id) }
        pump()
    }

    private fun enqueue(ids: List<PackId>, waitForWifi: Boolean, mobile: Boolean) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            queue.add(ids, waitForWifi = waitForWifi, mobile = mobile)
            for (id in ids) if (queue.active != id) store.setJob(id, Job.Queued)
        }
        pump()
    }

    /** Stops a download (its partial file is kept, to resume later) or takes it off the queue. */
    fun cancel(id: PackId) {
        val job: kotlinx.coroutines.Job?
        synchronized(lock) {
            job = if (queue.cancel(id)) activeJob.also { activeJob = null } else null
            store.setJob(id, null)
        }
        job?.cancel()
        pump()
    }

    /** True while something is queued, waiting or downloading. */
    val pending: Boolean get() = synchronized(lock) { !queue.isEmpty }

    /** A download is running now. */
    val running: Boolean get() = synchronized(lock) { queue.active != null }

    /** The pack downloading now, if any. */
    val current: PackId? get() = synchronized(lock) { queue.active }

    /** Looks at the queue again: marks what waits, pauses what may not go on, starts the next. */
    private fun pump() {
        var toPause: kotlinx.coroutines.Job? = null
        var started = false
        synchronized(lock) {
            // Installed and current (done while paused, or by another way): nothing to wait for.
            for (id in queue.ids) if (store.status(id).installed == Installed.READY) {
                queue.dropReady(id)
                if (queue.active != id) store.setJob(id, null)
            }
            val plan = queue.plan(prefs.rule, net) { bytesLeft(it) }
            for ((id, d) in plan.decisions) {
                if (id == queue.active) continue
                store.setJob(
                    id,
                    when (d) {
                        NetDecision.START -> Job.Queued
                        NetDecision.WAIT_NETWORK -> Job.Waiting(wifi = false)
                        else -> Job.Waiting(wifi = true)
                    },
                )
            }
            val a = queue.active
            if (plan.pause && a != null) {
                toPause = activeJob
                val d = plan.decisions[a]
                store.setJob(a, Job.Waiting(wifi = d != NetDecision.WAIT_NETWORK))
            }
            plan.start?.let {
                start(it)
                started = true
            }
        }
        toPause?.cancel()
        if (started) {
            ModelDownloadService.start(app)
            if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.notificationsAsked &&
                app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications = true
            }
        }
    }

    /** Called under [lock]. */
    private fun start(id: PackId) {
        val token = queue.started(id)
        store.setJob(id, Job.Downloading(0, store.registry[id].bytes))
        activeJob = scope.launch {
            var failed = false
            try {
                download(id)
                justReady = id
            } catch (e: CancellationException) {
                // Cancelled by the user or paused by the network (then it waits in the queue).
            } catch (e: Exception) {
                Log.w("ONIT", "model ${id.key} not downloaded", e)
                failed = true
                store.setJob(id, Job.Failed(reasonOf(e)))
            } finally {
                synchronized(lock) {
                    val left = queue.ended(id, token, ready = store.status(id).installed == Installed.READY)
                    // Done, or cancelled and not asked for again: no progress left showing (a late
                    // progress report included). A paused one, or one asked for again, gets its
                    // state from pump().
                    if (!failed && (left || id !in queue)) store.setJob(id, null)
                    if (queue.active == null) activeJob = null
                }
                pump()
            }
        }
    }

    private fun reasonOf(e: Exception): FailReason = when (e) {
        is NoSpace -> FailReason.NO_SPACE
        is Downloader.ChecksumMismatch -> FailReason.CHECKSUM
        is Downloader.HttpStatus, is Downloader.RefusedUrl -> FailReason.SERVER
        else -> FailReason.NETWORK
    }

    private class NoSpace : IOException("not enough space")

    /**
     * Downloads every file of [id] that isn't there yet. An installed pack (an update) gets its
     * new files under other names, switched in when nothing uses the pack ([ModelStore.finishUpdate]):
     * files in use are never overwritten.
     */
    private suspend fun download(id: PackId) {
        val p = store.registry[id]
        val total = p.bytes
        val update = store.status(id).usable
        var before = 0L
        val ctx = kotlin.coroutines.coroutineContext
        for (f in p.files) {
            val current = File(store.dir, f.name)
            if (Hashing.matches(current, f) { !ctx.isActive }) {
                before += f.expectedSize
                continue
            }
            val dest = if (update) store.stagedFile(f.name) else current
            if (update && Hashing.matches(dest, f) { !ctx.isActive }) {
                before += f.expectedSize
                continue
            }
            // Not in use (the pack isn't installed): an older file that isn't the registry's goes
            // now (a different Hebrew model of the same name, say), not after the new one is in.
            if (!update && current.exists()) current.delete()
            val part = File(store.dir, dest.name + ".part")
            val size = f.expectedSize
            val need = size - part.length().coerceIn(0L, size)
            val free = runCatching { StatFs(store.dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < need + SPACE_MARGIN) throw NoSpace()
            val base = before
            store.setJob(id, Job.Downloading(base + part.length().coerceIn(0L, size), total))
            downloader.fetch(
                f, part, dest,
                cancelled = { !ctx.isActive },
                onVerifying = { store.setJob(id, Job.Verifying) },
                onBytes = { b -> store.setJob(id, Job.Downloading(base + b, total)) },
            )
            before += size
        }
        val ok = if (update) store.finishUpdate(id) else store.markInstalled(id)
        if (!ok) throw IOException("pack ${id.key} incomplete")
    }

    companion object {
        /** Kept free beyond the file itself. */
        private const val SPACE_MARGIN = 50L * 1_000_000L

        @Volatile private var instance: ModelDownloads? = null

        fun get(context: Context): ModelDownloads {
            instance?.let { return it }
            return synchronized(this) { instance ?: ModelDownloads(context).also { instance = it } }
        }
    }
}
