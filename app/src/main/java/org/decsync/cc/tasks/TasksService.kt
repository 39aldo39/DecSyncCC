package org.decsync.cc.tasks

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import at.bitfire.ical4android.AndroidTaskList
import at.bitfire.ical4android.TaskProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.*
import org.dmfs.tasks.contract.TaskContract
import java.lang.Exception

open class TasksService : Service() {

    private var mTasksSyncAdapter: TasksSyncAdapter? = null

    override fun onCreate() {
        super.onCreate()
        if (mTasksSyncAdapter == null) {
            mTasksSyncAdapter = TasksSyncAdapter(applicationContext, true)
        }
    }

    override fun onBind(intent: Intent): IBinder? = mTasksSyncAdapter?.syncAdapterBinder

    internal inner class TasksSyncAdapter(context: Context, autoInitialize: Boolean) : AbstractThreadedSyncAdapter(context, autoInitialize) {
        @ExperimentalStdlibApi
        override fun onPerformSync(account: Account, extras: Bundle,
                                   authority: String, provider: ContentProviderClient,
                                   syncResult: SyncResult) {
            runBlocking {
                val success = sync(context, account, authority, provider, ::startForeground)
                if (!success) {
                    syncResult.databaseError = true
                }
            }
        }
    }

    companion object {
        @ExperimentalStdlibApi
        suspend fun sync(context: Context, account: Account, authority: String, provider: ContentProviderClient, startForeground: suspend (Int, Notification) -> Unit): Boolean {
            if (!PrefUtils.getUseSaf(context) &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return false
            }

            PrefUtils.checkAppUpgrade(context)

            // Required for ical4android
            Thread.currentThread().contextClassLoader = context.classLoader

            val providerName = TaskProvider.ProviderName.fromAuthority(authority)
            for (permission in providerName.permissions) {
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            val taskProvider = TaskProvider.fromProviderClient(context, providerName, provider)

            // make sure account can be seen by task provider
            if (Build.VERSION.SDK_INT >= 26)
                AccountManager.get(context).setAccountVisibility(account, providerName.packageName, AccountManager.VISIBILITY_VISIBLE)

            try {
                val taskLists = AndroidTaskList.find(account, taskProvider, LocalTaskList.Factory, null, null)
                val decsyncDir = PrefUtils.getNativeFile(context)
                        ?: throw Exception(context.getString(R.string.settings_decsync_dir_not_configured))
                for (taskList in taskLists) {
                    val color = taskList.color!!
                    val info = TaskListInfo(taskList.syncId!!, taskList.name!!, color)
                    val extra = Extra(info, context, provider)
                    val decsync = getDecsync(info, context, decsyncDir)

                    val accountManager = AccountManager.get(context)
                    val isInitSyncKey = "${PrefUtils.IS_INIT_SYNC}-${taskList.syncId}"
                    val isInitSync = accountManager.getUserData(account, isInitSyncKey) == "1"
                    if (isInitSync) {
                        val notification = initSyncNotificationBuilder(context).apply {
                            setSmallIcon(R.drawable.ic_notification)
                            setContentTitle(context.getString(R.string.notification_adding_tasks, info.name))
                        }.build()
                        startForeground(info.notificationId, notification)

                        setNumProcessedEntries(extra, 0)
                        decsync.initStoredEntries()
                        decsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
                        accountManager.setUserData(account, isInitSyncKey, null)
                    } else {
                        // Detect changed color
                        if (color != taskList.oldColor) {
                            decsync.setEntry(listOf("info"), JsonPrimitive("color"), JsonPrimitive(String.format("#%06X", color and 0xFFFFFF)))

                            val values = ContentValues()
                            values.put(COLUMN_OLD_COLOR, color)
                            taskList.update(values)
                        }

                        // Detect deleted tasks
                        val deletedTasks = taskList.queryTasks(TaskContract.Tasks._DELETED, null)
                        for (task in deletedTasks) {
                            task.writeDeleteAction(decsync)
                            addToNumProcessedEntries(extra, -1)
                        }

                        // Detect dirty tasks
                        val dirtyTasks = taskList.queryTasks(TaskContract.Tasks._DIRTY, null)
                        for (task in dirtyTasks) {
                            task.writeUpdateAction(decsync)
                            val isNewTask = task.isNewTask
                            if (isNewTask) {
                                task.isNewTask = false
                                addToNumProcessedEntries(extra, 1)
                            }
                        }

                        decsync.executeAllNewEntries(extra)
                    }
                }
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
                return false
            }

            return true
        }
    }
}

class OpenTasksService : TasksService()
class TasksOrgService : TasksService()

class TasksWorker(val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @ExperimentalStdlibApi
    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SYNC_STATS) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure()
        }

        val authority = PrefUtils.getTasksAuthority(context) ?: return Result.success()
        val tasksAccount = Account(PrefUtils.getTasksAccountName(context), context.getString(R.string.account_type_tasks))
        if (ContentResolver.isSyncActive(tasksAccount, authority)) {
            return Result.success()
        }

        val provider = context.contentResolver.acquireContentProviderClient(authority) ?: return Result.failure()
        try {
            val success = TasksService.sync(context, tasksAccount, authority, provider) { id, notification ->
                setForeground(ForegroundInfo(id, notification))
            }
            return if (success) Result.success() else Result.failure()
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }
    }
}
