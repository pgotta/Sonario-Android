package ai.sonario.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * A minimal foreground service that keeps the app process at foreground priority
 * (and therefore exempt from Doze network restrictions) while a long summary
 * runs. It owns no summarize logic; the ViewModel's coroutine does the work. The
 * service exists only so Android won't freeze the network when the screen turns
 * off or the user switches apps.
 *
 * Start it before a summary with SummaryService.start(context), stop it when the
 * job finishes with SummaryService.stop(context).
 */
class SummaryService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Summarizing…"
        startForegroundCompat(text)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(text: String) {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sonario")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Summaries in progress",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Shown while Sonario is summarizing." }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "sonario_summaries"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String = "Summarizing…") {
            val i = Intent(context, SummaryService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SummaryService::class.java))
        }
    }
}
