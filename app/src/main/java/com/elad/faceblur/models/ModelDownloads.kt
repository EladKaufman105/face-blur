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

/** The user's model choices that persist: the network rule and whether the first-launch sheet was seen. */
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

    fun updateRule(r: NetworkRule) {
        rule = r
        sp.edit().putBoolean("wifi_only", r.wifiOnly).putInt("ask_over_mb", r.askOverMb).apply()
    }

    fun markIntroSeen() {
        introSeen = true
        sp.edit().putBoolean("intro_seen", true).apply()
    }
}

/** "Download on mobile data?": the packs a tap asked for and their size. */
data class MeteredQuestion(val packs: List<PackId>, val bytes: Long)

/**
 * The downloads of model packs: one at a time, in the order asked, each started only when the
 * network rule allows ([NetworkPolicy]): waiting ones start when the network changes. Nothing
 * downloads without a tap. While one runs, [ModelDownloadService] keeps the app alive and shows
 * the progress in a notification.
 */
class ModelDownloads private constructor(context: Context) {
    private val app = context.applicationContext
    val store = ModelStore.get(app)
    val prefs = ModelPrefs(app)
    private val downloader = Downloader()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** Packs asked for and not done, in order (the first one allowed runs). */
    private val queue = ArrayList<PackId>()
    /** Packs the user said may use mobile data ("Download now"). */
    private val mobileOk = HashSet<PackId>()
    private var active: PackId? = null
    private var activeJob: kotlinx.coroutines.Job? = null
    /** Stopped by a network change, not by the user: stays in the queue. */
    private val paused = HashSet<PackId>()

    /** The network now (for the screens: "No internet connection"). */
    var net by mutableStateOf(Net.NONE)
        private set

    /** A question for the screen to ask ("Download on mobile data?"), or null. */
    var question by mutableStateOf<MeteredQuestion?>(null)
        private set

    /** The last pack that became ready (the screen says so once), or null. */
    var justReady by mutableStateOf<PackId?>(null)

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

    private fun changed(n: Net) {
        if (n == net) return
        net = n
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
     * A tap on a download button: queues [ids] (those that can be downloaded), or asks first
     * ([question]) when they are too big for mobile data by the user's rule.
     * @return what happens now ([NetDecision.WAIT_NETWORK]: the screen says it starts when online).
     */
    fun request(ids: Collection<PackId>): NetDecision {
        val want = ids.distinct().filter { id ->
            val s = store.status(id)
            s.installed != Installed.PENDING && s.installed != Installed.READY && !s.busy && !s.waiting
        }
        if (want.isEmpty()) return NetDecision.START
        val bytes = want.sumOf { bytesLeft(it) }
        val d = NetworkPolicy.decide(prefs.rule, net, bytes, mobileOk = false, asking = true)
        if (d == NetDecision.ASK) {
            question = MeteredQuestion(want, bytes)
            return d
        }
        enqueue(want)
        return d
    }

    /** The answer to [question]: download now on mobile data, or wait for Wi-Fi. */
    fun answer(downloadNow: Boolean) {
        val q = question ?: return
        question = null
        if (downloadNow) synchronized(lock) { mobileOk += q.packs }
        enqueue(q.packs)
    }

    /** The question closed without an answer: nothing is queued. */
    fun dismissQuestion() {
        question = null
    }

    /** "Download now" on a download that waits for Wi-Fi. */
    fun downloadNow(id: PackId) {
        synchronized(lock) { mobileOk += id }
        pump()
    }

    private fun enqueue(ids: List<PackId>) {
        synchronized(lock) {
            for (id in ids) if (id !in queue) {
                queue += id
                store.setJob(id, Job.Queued)
            }
        }
        pump()
    }

    /** Stops a download (its partial file is kept, to resume later) or takes it off the queue. */
    fun cancel(id: PackId) {
        val job: kotlinx.coroutines.Job?
        synchronized(lock) {
            queue.remove(id)
            mobileOk.remove(id)
            job = if (active == id) activeJob else null
        }
        job?.cancel()
        store.setJob(id, null)
        pump()
    }

    /** True while something is queued, waiting or downloading. */
    val pending: Boolean get() = synchronized(lock) { queue.isNotEmpty() }

    /** A download is running now. */
    val running: Boolean get() = synchronized(lock) { active != null }

    /** The pack downloading now, if any. */
    val current: PackId? get() = synchronized(lock) { active }

    private fun decision(id: PackId) = NetworkPolicy.decide(prefs.rule, net, bytesLeft(id), id in mobileOk, asking = false)

    /** Looks at the queue again: marks what waits, pauses what may not go on, starts the next. */
    private fun pump() {
        var toPause: kotlinx.coroutines.Job? = null
        var toStart: PackId? = null
        synchronized(lock) {
            for (id in queue) {
                if (id == active) continue
                store.setJob(
                    id,
                    when (decision(id)) {
                        NetDecision.START -> Job.Queued
                        NetDecision.WAIT_NETWORK -> Job.Waiting(wifi = false)
                        else -> Job.Waiting(wifi = true)
                    },
                )
            }
            val a = active
            if (a != null) {
                val d = decision(a)
                if (d != NetDecision.START) {
                    paused += a
                    toPause = activeJob
                    store.setJob(a, Job.Waiting(wifi = d != NetDecision.WAIT_NETWORK))
                }
            } else {
                toStart = queue.firstOrNull { decision(it) == NetDecision.START }
                toStart?.let { start(it) }
            }
        }
        toPause?.cancel()
        if (toStart != null) ModelDownloadService.start(app)
    }

    /** Called under [lock]. */
    private fun start(id: PackId) {
        active = id
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
                var done = false
                synchronized(lock) {
                    if (active == id) {
                        active = null
                        activeJob = null
                    }
                    if (!paused.remove(id)) {
                        queue.remove(id)
                        mobileOk.remove(id)
                        done = true
                    }
                }
                // Done, or cancelled: no progress left showing (a late progress report included).
                if (done && !failed) store.setJob(id, null)
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

    private suspend fun download(id: PackId) {
        val p = store.registry[id]
        val total = p.bytes
        var before = 0L
        val ctx = kotlin.coroutines.coroutineContext
        for (f in p.files) {
            val dest = File(store.dir, f.name)
            if (Hashing.matches(dest, f) { !ctx.isActive }) {
                before += f.expectedSize
                continue
            }
            val part = File(store.dir, f.name + ".part")
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
        if (!store.markInstalled(id)) throw IOException("pack ${id.key} incomplete")
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
