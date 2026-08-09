package com.agentdeck.app.data.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.agentdeck.app.MainActivity
import com.agentdeck.app.R
import java.util.concurrent.atomic.AtomicInteger

class EmbeddedRuntimeService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本机 Codex 会话",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AgentDeck 正在运行 Codex")
            .setContentText("本机对话仍在处理")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "agentdeck_embedded_runtime"
        private const val NOTIFICATION_ID = 4201
        private val leases = AtomicInteger(0)

        fun acquire(context: Context) {
            if (leases.incrementAndGet() == 1) {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    serviceIntent(context),
                )
            }
        }

        fun release(context: Context) {
            val remaining = leases.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            if (remaining == 0) {
                context.applicationContext.stopService(
                    serviceIntent(context),
                )
            }
        }

        private fun serviceIntent(context: Context): Intent =
            Intent().setClass(context.applicationContext, EmbeddedRuntimeService::class.java)
    }
}
