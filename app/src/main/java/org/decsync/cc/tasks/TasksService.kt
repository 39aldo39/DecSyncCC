package org.decsync.cc.tasks

import android.accounts.Account
import android.app.Service
import android.content.*
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.runBlocking
import org.decsync.cc.App

@ExperimentalStdlibApi
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
        override fun onPerformSync(account: Account, extras: Bundle,
                                   authority: String, provider: ContentProviderClient,
                                   syncResult: SyncResult) {
            // Only queue as the SyncAdapter is canceled when it doesn't use the internet in the first minute
            // Mainly used to get notified about changes in the data
            runBlocking {
                val decsyncDir = App.db.decsyncDirectoryDao().findByTaskListAccountName(account.name)
                if (decsyncDir != null) {
                    TasksWorker.enqueueDir(context, decsyncDir)
                }
            }
        }
    }
}

@ExperimentalStdlibApi
class OpenTasksService : TasksService()
@ExperimentalStdlibApi
class TasksOrgService : TasksService()