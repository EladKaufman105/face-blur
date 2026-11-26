package com.elad.faceblur.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.elad.faceblur.MainActivity
import com.elad.faceblur.i18n.t
import com.elad.faceblur.ui.packName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps model downloads going with the app in the background: a foreground service (data sync)
 * with the progress in a notification, for as long as a download runs. The downloads themselves
 * are [ModelDownloads]; if the system doesn't let this service start (the app is in the
 * background when Wi-Fi comes back), they run anyway while the app's process lives.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloads = ModelDownloads.get(this)
        if (intent?.action == ACTION_CANCEL) {
            downloads.current?.let { downloads.cancel(it) }
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            startForeground(NOTIFICATION_ID, notification(downloads), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            android.util.Log.w("ONIT", "model download service not in the foreground", e)
        }
        if (running) return START_NOT_STICKY
        running = true
        scope.launch {
            // Follows the downloads until none runs for a moment (the next one in the queue starts right after the last).
            var idle = 0
            while (idle < 3) {
                if (downloads.running) {
                    idle = 0
                    runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(downloads)) }
                } else {
                    idle++
                }
                delay(1000)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** The system's limit for data sync was reached: stop (the partial file is kept, and resumes later). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val downloads = ModelDownloads.get(this)
        downloads.current?.let { downloads.cancel(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(d: ModelDownloads): Notification {
        val id = d.current
        val job = id?.let { d.store.status(it).job }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(t("מוריד מודלים"))
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (id != null) b.setContentText(packName(id))
        when (job) {
            is Job.Downloading -> b.setProgress(100, (job.fraction * 100).roundToInt(), false)
            is Job.Verifying -> b.setProgress(0, 0, true).setSubText(t("בודק…"))
            else -> b.setProgress(0, 0, true)
        }
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        b.addAction(0, t("ביטול"), cancel)
        return b.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, t("הורדת מודלים"), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "models"
        private const val NOTIFICATION_ID = 51
        private const val ACTION_CANCEL = "com.elad.faceblur.models.CANCEL"

        /**
         * Starts the service for a download that begins. A plain start (allowed while the app is
         * on screen; the service then goes foreground itself), never a foreground start the
         * system could hold the app to: in the background it is refused and the download runs anyway.
         */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, ModelDownloadService::class.java))
            } catch (e: RuntimeException) {
                android.util.Log.i("ONIT", "model download service not started: $e")
            }
        }
    }
}
