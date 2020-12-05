package org.decsync.cc

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

@ExperimentalStdlibApi
abstract class InitWorker(val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    abstract val notificationTitleResId: Int
    abstract fun getCollectionInfo(id: String, name: String): CollectionInfo

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()

        val info = getCollectionInfo(id, name)

        val notification = initSyncNotificationBuilder(context).apply {
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(context.getString(notificationTitleResId, info.name))
        }.build()
        setForeground(ForegroundInfo(info.notificationId, notification))

        val provider = info.getProviderClient(context) ?: return Result.failure()
        try {
            val decsync = getDecsync(info, context)
            val extra = Extra(info, context, provider)
            setNumProcessedEntries(extra, 0)
            decsync.initStoredEntries()
            decsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }

        return Result.success()
    }

    companion object {
        const val KEY_ID = "KEY_ID"
        const val KEY_NAME = "KEY_NAME"
    }
}