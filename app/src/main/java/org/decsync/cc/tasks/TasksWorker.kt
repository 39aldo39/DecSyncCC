package org.decsync.cc.tasks

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import at.bitfire.ical4android.AndroidTaskList
import at.bitfire.ical4android.TaskProvider
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.*
import org.decsync.cc.R
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.library.Decsync
import org.decsync.library.NativeFile
import org.dmfs.tasks.contract.TaskContract

@ExperimentalStdlibApi
class TasksWorker(context: Context, params: WorkerParameters) : CollectionWorker<Unit>(context, params) {
    override val initSyncNotificationTitleResId = R.string.notification_adding_tasks

    override fun getCollectionInfo(decsyncDir: DecsyncDirectory, id: String, name: String): CollectionInfo {
        return TaskListInfo(decsyncDir, id, name, null, false)
    }

    override suspend fun sync(info: CollectionInfo, provider: ContentProviderClient, nativeFile: NativeFile): Boolean {
        if (!PrefUtils.getUseSaf(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        PrefUtils.checkAppUpgrade(context)

        // Required for ical4android
        Thread.currentThread().contextClassLoader = context.classLoader

        val authority = PrefUtils.getTasksAuthority(context) ?: return false
        val account = info.getAccount(context)
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

        val taskList = LocalTaskList.findBySyncId(account, taskProvider, info.id) ?: return false
        val color = taskList.color!!
        val extra = Extra(info, context, provider)
        val decsync = getDecsync(info, context, nativeFile)

        // Detect changed color
        if (color != taskList.oldColor) {
            decsync.setEntry(listOf("info"), JsonPrimitive("color"), JsonPrimitive(Utils.colorToString(color)))

            val values = ContentValues()
            values.put(TasksUtils.COLUMN_OLD_COLOR, color)
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
        return true
    }

    override val importNotificationTitleResId = R.string.notification_importing_events

    override fun getItems(provider: ContentProviderClient, info: CollectionInfo): List<Unit> = throw Exception("Importing tasks is not supported")
    override fun writeItemDecsync(decsync: Decsync<Extra>, item: Unit) = throw Exception("Importing tasks is not supported")
    override fun writeItemAndroid(info: CollectionInfo, provider: ContentProviderClient, item: Unit) = throw Exception("Importing tasks is not supported")

    companion object {
        suspend fun enqueueAll(context: Context) {
            val decsyncDirs = App.db.decsyncDirectoryDao().all()
            for (decsyncDir in decsyncDirs) {
                enqueueDir(context, decsyncDir)
            }
        }

        fun enqueueDir(context: Context, decsyncDir: DecsyncDirectory) {
            val authority = PrefUtils.getTasksAuthority(context) ?: return
            val providerName = TaskProvider.ProviderName.fromAuthority(authority)
            for (permission in providerName.permissions) {
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }

            val provider = context.contentResolver.acquireContentProviderClient(authority) ?: return
            try {
                val account = decsyncDir.getTaskListAccount(context)
                val taskProvider = TaskProvider.fromProviderClient(context, providerName, provider)
                val taskLists = AndroidTaskList.find(account, taskProvider, LocalTaskList.Factory, null, null)
                for (taskList in taskLists) {
                    val info = TaskListInfo(decsyncDir, taskList.syncId!!, taskList.name!!, null, false)
                    enqueue(context, info)
                }
            } finally {
                if (Build.VERSION.SDK_INT >= 24)
                    provider.close()
                else
                    @Suppress("DEPRECATION")
                    provider.release()
            }
        }
    }
}
