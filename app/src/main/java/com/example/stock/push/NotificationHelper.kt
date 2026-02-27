package com.example.stock.push

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.stock.MainActivity
import com.example.stock.R

object NotificationHelper {
    private const val CHANNEL_ID = "korea_stock_dash_alerts"
    private const val UPDATES_CHANNEL_ID = "korea_stock_dash_updates"
    private const val UPDATE_NOTIFICATION_ID = 12001
    private const val UPDATE_PENDING_INTENT_ID = 12001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KoreaStockDash Alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

            val updates = NotificationChannel(
                UPDATES_CHANNEL_ID,
                "KoreaStockDash Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(updates)
        }
    }

    fun show(context: Context, title: String, body: String, route: String?) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("route", route)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notifySafely(context, System.currentTimeMillis().toInt(), notification)
    }

    fun showUpdate(context: Context, title: String, body: String, apkUrl: String) {
        ensureChannel(context)
        clearUpdateNotifications(context)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_PENDING_INTENT_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, UPDATES_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()

        notifySafely(context, UPDATE_NOTIFICATION_ID, notification)
    }

    fun clearUpdateNotifications(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.activeNotifications
                .filter { it.notification.channelId == UPDATES_CHANNEL_ID }
                .forEach { sbn -> nm.cancel(sbn.id) }
        } else {
            NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(
        context: Context,
        notificationId: Int,
        notification: android.app.Notification,
    ) {
        if (!hasNotificationPermission(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
