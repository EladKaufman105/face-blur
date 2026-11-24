package com.elad.faceblur.batch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elad.faceblur.MainActivity
import com.elad.faceblur.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps a batch going with the screen off and the app in the background: a foreground service
 * with a progress notification. The work itself is [BatchRunner]; the batch is in [BatchState].
 */
class BatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var running = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batch = BatchState.current
        if (intent?.action == ACTION_CANCEL) {
            batch?.cancelAll()
            return START_NOT_STICKY
        }
        if (batch == null || batch.finished) {
            // Started by the system for a batch that's gone (the process was restarted): nothing to do.
            ensureChannel()
            startAsForeground(notification(null))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        ensureChannel()
        startAsForeground(notification(batch))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "onit:batch").apply { acquire(WAKE_LIMIT_MS) }
        scope.launch {
            try {
                BatchRunner.run(this@BatchService, batch, scope) { update(batch, false) }
            } catch (e: Throwable) {
                android.util.Log.w("ONIT", "batch stopped", e)
            } finally {
                batch.finished = true
                finish(batch)
            }
        }
        return START_NOT_STICKY
    }

    /** The system's time limit for this kind of service was reached: stop what's left, keep what's done. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        BatchState.current?.cancelAll()
        // The system wants the service gone within seconds; the cancelled batch finishes on its own.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        BatchState.current?.takeIf { !it.finished }?.cancelAll()
        scope.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    /**
     * Becomes a foreground service: media processing where the system has it (Android 15+), data
     * sync before, each one tried in turn, called on the platform directly: androidx's
     * ServiceCompat drops the media-processing type (its mask predates Android 15), which made
     * every Android 15+ phone refuse the service as "type none". If every type is refused, the
     * service stays an ordinary one and the batch runs while the app is open.
     */
    private fun startAsForeground(n: Notification): Boolean {
        val types = buildList {
            if (Build.VERSION.SDK_INT >= 35) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        for (type in types) {
            try {
                startForeground(NOTIFICATION_ID, n, type)
                return true
            } catch (e: RuntimeException) {
                android.util.Log.w("ONIT", "foreground service type $type refused", e)
            }
        }
        return false
    }

    private fun update(batch: Batch, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(batch)) }
    }

    private fun finish(batch: Batch) {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        val text = if (batch.cancelled) t("העיבוד הופסק. %d סרטונים נשמרו.").format(batch.savedCount)
        else t("%d סרטונים נשמרו").format(batch.savedCount) + (if (batch.checkCount > 0) " · " + t("%d לבדיקה").format(batch.checkCount) else "")
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(t("עיבוד הסרטונים הסתיים"))
            .setContentText(text)
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(DONE_ID, n) }
        stopSelf()
    }

    private fun notification(batch: Batch?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (batch == null) return b.setContentTitle("ONIT").build()
        // On the lock screen: the count only, never the videos' names.
        b.setPublicVersion(
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(t("מעבד סרטונים · %d מתוך %d").format((batch.doneCount + 1).coerceAtMost(batch.items.size), batch.items.size))
                .build(),
        )
        val cur = batch.current
        val n = batch.items.size
        val pos = (batch.doneCount + 1).coerceAtMost(n)
        b.setContentTitle(t("מעבד סרטונים · %d מתוך %d").format(pos, n))
        b.setContentText(cur?.let { it.name + " · " + statusText(it.status, batch) } ?: t("מתחיל…"))
        b.setProgress(100, (batch.progress * 100).roundToInt().coerceIn(0, 100), false)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, BatchService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        b.addAction(0, t("בטל הכל"), cancel)
        return b.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, t("עיבוד סרטונים"), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "batch"
        private const val NOTIFICATION_ID = 41
        private const val DONE_ID = 42
        private const val ACTION_CANCEL = "com.elad.faceblur.batch.CANCEL"
        private const val WAKE_LIMIT_MS = 6L * 60 * 60 * 1000

        /** Starts the foreground service for [BatchState.current]. */
        fun start(context: Context) {
            val intent = Intent(context, BatchService::class.java)
            // A batch starts from the screen, so a plain start is allowed. It then becomes a
            // foreground service itself; if the system refuses that, it stays an ordinary service
            // (runs while the app is open) instead of being killed for not going foreground in time.
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun statusText(status: ItemStatus, batch: Batch): String = when (status) {
            ItemStatus.WAITING -> t("ממתין")
            ItemStatus.ANALYZING -> t("מזהה פנים")
            ItemStatus.PROCESSING -> if (batch.settings.createSubs) t("כתוביות ועיבוד") else t("עיבוד")
            ItemStatus.EXPORTING -> t("מייצא")
            ItemStatus.SAVED -> t("נשמר")
            ItemStatus.FAILED -> t("נכשל")
            ItemStatus.NO_FACES -> t("לא נמצאו פנים")
            ItemStatus.SKIPPED -> t("דולג")
        }
    }
}
