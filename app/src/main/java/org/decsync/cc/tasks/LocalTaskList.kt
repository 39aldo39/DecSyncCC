package org.decsync.cc.tasks

import android.accounts.Account
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import at.bitfire.ical4android.AndroidTaskList
import at.bitfire.ical4android.AndroidTaskListFactory
import at.bitfire.ical4android.TaskProvider
import org.decsync.cc.TaskListInfo
import org.dmfs.tasks.contract.TaskContract.TaskLists
import org.dmfs.tasks.contract.TaskContract.Tasks

@ExperimentalStdlibApi
class LocalTaskList private constructor(
        account: Account,
        provider: TaskProvider,
        id: Long
): AndroidTaskList<LocalTask>(account, provider, LocalTask.Factory, id) {

    val oldColor: Int?
    get() {
        provider.client.query(taskListSyncUri(), arrayOf(COLUMN_OLD_COLOR),
                null, null, null)?.use { cursor ->
            if (cursor.moveToNext())
                return cursor.getInt(0)
        }
        return null
    }

    var numProcessedEntries: Int
    get() {
        provider.client.query(taskListSyncUri(), arrayOf(COLUMN_NUM_PROCESSED_ENTRIES),
                null, null, null)?.use { cursor ->
            if (cursor.moveToNext())
                return cursor.getInt(0)
        }
        return 0
    }
    set(value) {
        val values = ContentValues()
        values.put(COLUMN_NUM_PROCESSED_ENTRIES, value)
        provider.client.update(taskListSyncUri(), values, null, null)
    }

    companion object {
        fun create(account: Account, provider: TaskProvider, info: TaskListInfo): Uri {
            val color = info.color ?: Color.BLACK
            val values = ContentValues()
            values.put(TaskLists._SYNC_ID, info.id)
            values.put(TaskLists.LIST_NAME, info.name)
            values.put(TaskLists.LIST_COLOR, color)
            values.put(COLUMN_OLD_COLOR, color)
            values.put(TaskLists.SYNC_ENABLED, 1)
            values.put(TaskLists.VISIBLE, 1)
            return create(account, provider, values)
        }

        fun findBySyncId(account: Account, provider: TaskProvider, syncId: String): LocalTaskList? {
            return find(account, provider, Factory,
                    "${TaskLists._SYNC_ID}=?", arrayOf(syncId)
            ).firstOrNull()
        }
    }

    fun findByUid(uid: String): LocalTask? {
        return queryTasks("${Tasks._UID}=?", arrayOf(uid)).firstOrNull()
    }

    object Factory: AndroidTaskListFactory<LocalTaskList> {
        override fun newInstance(account: Account, provider: TaskProvider, id: Long) =
                LocalTaskList(account, provider, id)
    }
}