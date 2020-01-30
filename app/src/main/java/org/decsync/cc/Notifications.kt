package org.decsync.cc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

const val CHANNEL_INIT_SYNC = "initial_sync"

private fun createInitSyncNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val name = context.getString(R.string.channel_init_sync_name)
        val descriptionText = context.getString(R.string.channel_init_sync_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_INIT_SYNC, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

fun initSyncNotificationBuilder(context: Context): NotificationCompat.Builder {
    createInitSyncNotificationChannel(context)

    return NotificationCompat.Builder(context, CHANNEL_INIT_SYNC).apply {
        priority = NotificationCompat.PRIORITY_LOW
        setProgress(0, 0, true)
        setOngoing(true)
    }
}