package org.decsync.cc.tasks

import org.dmfs.tasks.contract.TaskContract

object TasksUtils {
    const val COLUMN_OLD_COLOR = TaskContract.TaskLists.SYNC1
    const val COLUMN_NUM_PROCESSED_ENTRIES = TaskContract.TaskLists.SYNC2
    const val COLUMN_IS_NEW_TASK = TaskContract.Tasks.SYNC1
}