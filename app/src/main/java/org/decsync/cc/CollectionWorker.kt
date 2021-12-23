package org.decsync.cc

import android.app.PendingIntent
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.decsync.cc.calendars.CalendarsWorker
import org.decsync.cc.contacts.ContactsWorker
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.cc.tasks.TasksWorker
import org.decsync.cc.ui.MainActivity
import org.decsync.library.Decsync
import org.decsync.library.NativeFile
import java.lang.Exception
import java.util.concurrent.TimeUnit

private const val TAG = "CollectionWorker"

@ExperimentalStdlibApi
abstract class CollectionWorker<Item>(val context: Context, params: WorkerParameters): CoroutineWorker(context, params) {

    abstract val initSyncNotificationTitleResId: Int
    abstract fun getCollectionInfo(decsyncDir: DecsyncDirectory, id: String, name: String): CollectionInfo
    abstract suspend fun sync(info: CollectionInfo, provider: ContentProviderClient, nativeFile: NativeFile): Boolean

    // Import functions
    abstract val importNotificationTitleResId: Int
    abstract fun getItems(provider: ContentProviderClient, info: CollectionInfo): List<Item>
    abstract fun writeItemDecsync(decsync: Decsync<Extra>, item: Item)
    abstract fun writeItemAndroid(info: CollectionInfo, provider: ContentProviderClient, item: Item)

    override suspend fun doWork(): Result {
        val dirName = inputData.getString(KEY_DIR_NAME) ?: return Result.failure()
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()

        val decsyncDir = App.db.decsyncDirectoryDao().findByName(dirName) ?: return Result.failure()
        val info = getCollectionInfo(decsyncDir, id, name)
        Log.d(TAG, "Sync collection ${info.syncType}-${info.id}")

        val provider = info.getProviderClient(context) ?: return Result.failure()
        try {
            val nativeFile = decsyncDir.getNativeFile(context)
            val syncKind = PrefUtils.getSyncKind(context, info)
            return when (syncKind) {
                SYNC_KIND_STANDARD -> {
                    try {
                        val success = sync(info, provider, nativeFile)
                        if (success) Result.success() else Result.failure()
                    } catch (e: Exception) {
                        Log.e(TAG, "", e)
                        val builder = errorNotificationBuilder(context).apply {
                            setSmallIcon(R.drawable.ic_notification)
                            if (PrefUtils.getUpdateForcesSaf(context)) {
                                setContentTitle(context.getString(R.string.notification_saf_update_title))
                                setContentText(context.getString(R.string.notification_saf_update_message))
                            } else {
                                setContentTitle("DecSync")
                                setContentText(e.message)
                            }
                            setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), 0))
                            setAutoCancel(true)
                        }
                        with(NotificationManagerCompat.from(context)) {
                            notify(0, builder.build())
                        }
                        Result.failure()
                    }
                }
                SYNC_KIND_INIT_SYNC -> {
                    val notification = initSyncNotificationBuilder(context).apply {
                        setSmallIcon(R.drawable.ic_notification)
                        setContentTitle(context.getString(initSyncNotificationTitleResId, info.name))
                    }.build()
                    setForeground(ForegroundInfo(info.notificationId, notification))

                    val decsync = getDecsync(info, context, nativeFile)
                    val extra = Extra(info, context, provider)
                    decsync.initStoredEntries()
                    decsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
                    PrefUtils.putSyncKind(context, info, SYNC_KIND_STANDARD)
                    Result.success()
                }
                SYNC_KIND_IMPORT -> {
                    val notification = importNotificationBuilder(context).apply {
                        setSmallIcon(R.drawable.ic_notification)
                        setContentTitle(context.getString(importNotificationTitleResId, info.name))
                    }
                    setForeground(ForegroundInfo(info.notificationId, notification.build()))

                    val decsync = getDecsync(info, context, nativeFile)
                    val extra = Extra(info, context, provider)

                    val items = getItems(provider, info)
                    Log.d(TAG, "Importing ${items.size} items")
                    for (i in items.indices) {
                        val item = items[i]
                        Log.i(TAG, "Importing item $item")
                        notification.apply {
                            setProgress(items.size, i, false)
                        }
                        with(NotificationManagerCompat.from(context)) {
                            notify(info.notificationId, notification.build())
                        }
                        writeItemDecsync(decsync, item)
                        writeItemAndroid(info, provider, item)
                    }

                    PrefUtils.putSyncKind(context, info, SYNC_KIND_STANDARD)
                    PrefUtils.removeImportedAccount(context, info)
                    PrefUtils.removeImportedCalendarId(context, info)
                    Result.success()
                }
                else -> Result.failure()
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }
    }

    companion object {
        const val KEY_DIR_NAME = "KEY_DIR_NAME"
        const val KEY_ID = "KEY_ID"
        const val KEY_NAME = "KEY_NAME"

        const val SYNC_KIND_STANDARD = 0
        const val SYNC_KIND_INIT_SYNC = 1
        const val SYNC_KIND_IMPORT = 2

        fun enqueue(context: Context, info: CollectionInfo) {
            val workManager = WorkManager.getInstance(context)
            GlobalScope.launch {
                val workInfos = workManager.getWorkInfosForUniqueWork("${info.syncType}-${info.id}").await()
                if (workInfos.all { it.state != WorkInfo.State.RUNNING }) {
                    val inputData = Data.Builder()
                        .putString(KEY_DIR_NAME, info.decsyncDir.name)
                        .putString(KEY_ID, info.id)
                        .putString(KEY_NAME, info.name)
                        .build()
                    val workerClass = when (info) {
                        is AddressBookInfo -> ContactsWorker::class
                        is CalendarInfo -> CalendarsWorker::class
                        is TaskListInfo -> TasksWorker::class
                    }
                    // Set the repeatInterval slightly higher than the frequency of the SyncAdapter
                    // (20 vs 15 minutes), so we reduce the chance of two syncs occurring very close
                    // to each other.
                    val workRequest = PeriodicWorkRequest.Builder(workerClass.java, 20, TimeUnit.MINUTES)
                            .setInputData(inputData)
                            .build()
                    workManager.enqueueUniquePeriodicWork("${info.syncType}-${info.id}", ExistingPeriodicWorkPolicy.REPLACE, workRequest)
                }
            }
        }

        fun dequeue(context: Context, info: CollectionInfo) {
            WorkManager.getInstance(context).cancelUniqueWork("${info.syncType}-${info.id}")
        }
    }
}