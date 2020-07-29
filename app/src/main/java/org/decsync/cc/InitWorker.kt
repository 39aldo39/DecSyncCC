package org.decsync.cc

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

@ExperimentalStdlibApi
abstract class InitWorker(val context: Context, params: WorkerParameters) : Worker(context, params) {

    abstract val type: CollectionInfo.Type
    abstract val authority: String

    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()

        val info = CollectionInfo(type, id, name, context)

        val builder = initSyncNotificationBuilder(context).apply {
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(context.getString(
                    when (type) {
                        CollectionInfo.Type.ADDRESS_BOOK -> R.string.notification_adding_contacts
                        CollectionInfo.Type.CALENDAR -> R.string.notification_adding_events
                    },
                    info.name))
        }
        with(NotificationManagerCompat.from(context)) {
            notify(info.notificationId, builder.build())
        }

        val result = context.contentResolver.acquireContentProviderClient(authority)?.let { provider ->
            try {
                val decsync = getDecsync(info)
                val extra = Extra(info, context, provider)
                setNumProcessedEntries(extra, 0)
                decsync.initStoredEntries()
                decsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
                Result.success()
            } finally {
                if (Build.VERSION.SDK_INT >= 24)
                    provider.close()
                else
                    @Suppress("DEPRECATION")
                    provider.release()
            }
        } ?: Result.failure()

        with(NotificationManagerCompat.from(context)) {
            cancel(info.notificationId)
        }
        return result
    }

    companion object {
        const val KEY_ID = "KEY_ID"
        const val KEY_NAME = "KEY_NAME"
    }
}