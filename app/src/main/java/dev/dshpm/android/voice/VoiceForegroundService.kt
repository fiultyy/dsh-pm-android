package dev.dshpm.android.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.dshpm.android.R

/**
 * AND5-1: voice-session keep-alive foreground service (spec-android §3
 * AND-003, 验收②).
 *
 * The service owns NO audio: mic/player stay bound to [VoiceController] in
 * the activity process. Its only jobs are (a) the mandatory mic-type
 * foreground notification while a voice session is up, and (b) holding the
 * process out of the cached-background trim path so the ONE shared WebSocket
 * and the audio pipeline survive lock screen / backgrounding (≥10min).
 *
 * Started/stopped from MainActivity when the voice tab session goes
 * active/inactive; start happens only while the app is foreground (while-in-
 * use mic FGS rule on API 34+) and only with RECORD_AUDIO already granted.
 */
class VoiceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, dev.dshpm.android.MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getText(R.string.voice_notif_title))
            .setContentText(getText(R.string.voice_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice"
        private const val NOTIFICATION_ID = 0x5EE1

        const val ACTION_STOP = "dev.dshpm.android.voice.STOP"

        /** FGS lifecycle for MainActivity (idempotent — repeats are no-ops). */
        fun start(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getText(R.string.voice_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            // stopService removes the notification with the service window.
            context.stopService(Intent(context, VoiceForegroundService::class.java))
        }
    }
}
