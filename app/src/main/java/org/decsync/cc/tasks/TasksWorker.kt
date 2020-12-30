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
import org.dmfs.tasks.contract.TaskContract

class TasksWorker(context: Context, params: WorkerParameters) : CollectionWorker(context, params) {
    override val notificationTitleResId = R.string.notification_adding_tasks

    @ExperimentalStdlibApi
    override fun getCollectionInfo(id: String, name: String): CollectionInfo {
        return TaskListInfo(id, name, null)
    }

    @ExperimentalStdlibApi
    override fun sync(info: CollectionInfo, provider: ContentProviderClient): Boolean {
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
        val decsyncDir = PrefUtils.getNativeFile(context)
                ?: throw Exception(context.getString(R.string.settings_decsync_dir_not_configured))
        val color = taskList.color!!
        val extra = Extra(info, context, provider)
        val decsync = getDecsync(info, context, decsyncDir)

        // Detect changed color
        if (color != taskList.oldColor) {
            decsync.setEntry(listOf("info"), JsonPrimitive("color"), JsonPrimitive(Utils.colorToString(color)))

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
        return true
    }

    companion object {
        @ExperimentalStdlibApi
        fun enqueueAll(context: Context) {
            val authority = PrefUtils.getTasksAuthority(context) ?: return
            val provider = context.contentResolver.acquireContentProviderClient(authority) ?: return
            try {
                val providerName = TaskProvider.ProviderName.fromAuthority(authority)
                for (permission in providerName.permissions) {
                    if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                        return
                    }
                }
                val account = Account(PrefUtils.getTasksAccountName(context), context.getString(R.string.account_type_tasks))
                val taskProvider = TaskProvider.fromProviderClient(context, providerName, provider)
                val taskLists = AndroidTaskList.find(account, taskProvider, LocalTaskList.Factory, null, null)
                for (taskList in taskLists) {
                    val info = TaskListInfo(taskList.syncId!!, taskList.name!!, null)
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
