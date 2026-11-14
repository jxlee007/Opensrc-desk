package com.example.service

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
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.PerformanceAdvisory

class ExtendedDesktopService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WorkstationRuntime.getIfInitialized()?.stopAllFromServiceNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: TITLE_LIVE
        val line1 = intent?.getStringExtra(EXTRA_LINE_1) ?: "Display apps: 0 running"
        val line2 = intent?.getStringExtra(EXTRA_LINE_2) ?: ""

        val notification = buildNotification(this, title, line1, line2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // VirtualDisplay, DesktopShellActivity, H264StreamEncoder, and sockets belong to this
        // foreground service and must survive closing MainActivity or swiping it from Recents.
    }

    companion object {
        private const val CHANNEL_ID = "extdesk_link_channel"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_STOP = "com.example.action.STOP_EXTDESK"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LINE_1 = "line_1"
        const val EXTRA_LINE_2 = "line_2"
        const val TITLE_LIVE = "Extended Desktop · Live"
        const val TITLE_STANDBY = "Extended Desktop · Standby"

        fun startHost(context: Context, advisory: PerformanceAdvisory) {
            val intent = Intent(context, ExtendedDesktopService::class.java).apply {
                putExtra(EXTRA_TITLE, TITLE_LIVE)
                putExtra(EXTRA_LINE_1, advisory.notificationLine1())
                putExtra(EXTRA_LINE_2, advisory.notificationLine2())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateHostNotification(context: Context, advisory: PerformanceAdvisory) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notification = buildNotification(
                context = context,
                title = TITLE_LIVE,
                line1 = advisory.notificationLine1(),
                line2 = advisory.notificationLine2()
            )
            mgr.notify(NOTIFICATION_ID, notification)
        }

        fun startDisplayStandby(context: Context, port: Int) {
            val intent = Intent(context, ExtendedDesktopService::class.java).apply {
                putExtra(EXTRA_TITLE, TITLE_STANDBY)
                putExtra(EXTRA_LINE_1, "Display listening on port $port")
                putExtra(EXTRA_LINE_2, "Long-press screen for Info & QR")
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun updateDisplayNotification(
            context: Context,
            line1: String,
            line2: String,
            title: String = TITLE_LIVE
        ) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notification = buildNotification(
                context = context,
                title = title,
                line1 = line1,
                line2 = line2
            )
            mgr.notify(NOTIFICATION_ID, notification)
        }

        fun startDisplay(context: Context, hostName: String) {
            updateDisplayNotification(
                context = context,
                line1 = "Display connected to $hostName",
                line2 = "Long-press screen for Disconnect & Info",
                title = TITLE_LIVE
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ExtendedDesktopService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Extended Desktop Link",
                    NotificationManager.IMPORTANCE_LOW
                )
                mgr.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(
            context: Context,
            title: String,
            line1: String,
            line2: String
        ): Notification {
            ensureChannel(context)
            val openIntent = Intent(context, MainActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(context, ExtendedDesktopService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPending = PendingIntent.getService(
                context,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(line1)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(0, "Stop", stopPending)

            if (line2.isNotBlank()) {
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText("$line1\n$line2")
                )
            }
            return builder.build()
        }
    }
}
