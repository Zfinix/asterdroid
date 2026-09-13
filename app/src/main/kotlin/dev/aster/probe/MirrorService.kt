package dev.aster.probe

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
import android.util.Log

/**
 * What makes the screen capture legal. Since Android 14 a projection is only
 * granted to an app the user can see is recording, so this exists to be the
 * notification that says so; the capture itself lives in [Mirror].
 */
class MirrorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A refused foreground type throws, and an uncaught throw here would
        // kill the process the accessibility service lives in. Better to stop
        // and let the mirror report that it could not start.
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }
        if (started.isFailure) {
            Log.w(TAG, "the system refused the capture service: ${started.exceptionOrNull()}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Mirror.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Aster mirror", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows that the screen is being mirrored"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Aster mirror")
            .setContentText("The screen is being streamed")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "aster-mirror"
        private const val CHANNEL = "aster-mirror"
        private const val NOTIFICATION_ID = 2

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MirrorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MirrorService::class.java))
        }
    }
}
