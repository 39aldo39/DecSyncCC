package org.decsync.cc.tasks

import android.content.ContentValues
import at.bitfire.ical4android.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.Extra
import org.decsync.library.Decsync
import org.dmfs.tasks.contract.TaskContract
import java.io.ByteArrayOutputStream
import java.util.*

@ExperimentalStdlibApi
class LocalTask: AndroidTask {
    constructor(taskList: AndroidTaskList<*>, task: Task) : super(taskList, task)
    constructor(taskList: AndroidTaskList<*>, values: ContentValues): super(taskList, values)

    var isNewTask: Boolean
        get() {
            taskList.provider.client.query(taskSyncURI(), arrayOf(TasksUtils.COLUMN_IS_NEW_TASK),
                    null, null, null)?.use { cursor ->
                if (cursor.moveToNext())
                    return cursor.isNull(0)
            }
            return true
        }
        set(value) {
            val values = ContentValues()
            if (value) {
                values.put(TasksUtils.COLUMN_IS_NEW_TASK, 1)
            } else {
                values.putNull(TasksUtils.COLUMN_IS_NEW_TASK)
            }
            taskList.provider.client.update(taskSyncURI(), values, null, null)
        }

    fun writeDeleteAction(decsync: Decsync<Extra>) {
        val task = requireNotNull(task)
        val uid = task.uid

        if (uid != null) {
            decsync.setEntry(listOf("resources", uid), JsonNull, JsonNull)
        }

        super.delete()
    }

    fun writeUpdateAction(decsync: Decsync<Extra>) {
        val task = requireNotNull(task)
        val uid = task.uid ?: UUID.randomUUID().toString()
        task.uid = uid

        val os = ByteArrayOutputStream()
        task.write(os)
        val ical = os.toString("UTF-8")
        decsync.setEntry(listOf("resources", uid), JsonNull, JsonPrimitive(ical))

        val values = ContentValues()
        values.put(TaskContract.Tasks._SYNC_ID, uid)
        values.put(TaskContract.Tasks._UID, uid)
        values.put(TaskContract.Tasks._DIRTY, 0)
        taskList.provider.client.update(taskSyncURI(), values, null, null)
    }

    override fun buildTask(builder: BatchOperation.CpoBuilder, update: Boolean) {
        super.buildTask(builder, update)
        val task = requireNotNull(task)

        builder .withValue(TasksUtils.COLUMN_IS_NEW_TASK, 1)
                .withValue(TaskContract.Tasks._SYNC_ID, task.uid)
    }

    object Factory : AndroidTaskFactory<LocalTask> {
        override fun fromProvider(taskList: AndroidTaskList<*>, values: ContentValues) =
                LocalTask(taskList, values)
    }
}