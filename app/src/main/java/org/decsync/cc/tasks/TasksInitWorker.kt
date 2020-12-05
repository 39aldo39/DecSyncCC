package org.decsync.cc.tasks

import android.content.Context
import androidx.work.WorkerParameters
import org.decsync.cc.CollectionInfo
import org.decsync.cc.InitWorker
import org.decsync.cc.R
import org.decsync.cc.TaskListInfo

@ExperimentalStdlibApi
class TasksInitWorker(context: Context, params: WorkerParameters) : InitWorker(context, params) {
    override val notificationTitleResId = R.string.notification_adding_tasks

    override fun getCollectionInfo(id: String, name: String): CollectionInfo {
        return TaskListInfo(id, name, null)
    }
}