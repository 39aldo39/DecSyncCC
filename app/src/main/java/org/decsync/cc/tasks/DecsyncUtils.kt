package org.decsync.cc.tasks

import android.content.ContentValues
import android.graphics.Color
import android.util.Log
import at.bitfire.ical4android.Task
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.decsync.cc.Extra
import org.decsync.cc.TaskListInfo
import org.decsync.cc.addToNumProcessedEntries
import org.decsync.library.Decsync
import org.dmfs.tasks.contract.TaskContract
import java.io.StringReader

const val COLUMN_OLD_COLOR = TaskContract.TaskLists.SYNC1
const val COLUMN_NUM_PROCESSED_ENTRIES = TaskContract.TaskLists.SYNC2
const val COLUMN_IS_NEW_TASK = TaskContract.Tasks.SYNC1
const val TAG = "DecSync Tasks"

@ExperimentalStdlibApi
object TasksDecsyncUtils {
    fun infoListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute info entry $entry")
        val info = entry.key.jsonPrimitive.content

        val taskList = (extra.info as TaskListInfo).getTaskList(extra.context) ?: run {
            Log.w(TAG, "Could not find task list ${extra.info.id}")
            return
        }
        when (info) {
            "deleted" -> {
                val deleted = entry.value.jsonPrimitive.boolean
                if (!deleted) {
                    return
                }
                Log.d(TAG, "Delete task list ${extra.info.name}")

                taskList.delete()
            }
            "name" -> {
                val name = entry.value.jsonPrimitive.content
                if (extra.info.name == name) return
                Log.d(TAG, "Rename task list ${extra.info.name} to $name")

                val values = ContentValues()
                values.put(TaskContract.TaskLists.LIST_NAME, name)
                taskList.update(values)
            }
            "color" -> {
                val color = entry.value.jsonPrimitive.content
                Log.d(TAG, "Set color of calendar ${extra.info.name} to $color)")

                try {
                    val values = ContentValues()
                    val colorInt = Color.parseColor(color)
                    values.put(TaskContract.TaskLists.LIST_COLOR, colorInt)
                    values.put(COLUMN_OLD_COLOR, colorInt)
                    taskList.update(values)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Unknown color $color", e)
                }
            }
            else -> {
                Log.w(TAG, "Unknown info key $info")
            }
        }
    }

    fun resourcesListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        if (path.size != 1) {
            Log.w(TAG, "Invalid path $path")
            return
        }
        val uid = path[0]
        val ical = entry.value.jsonPrimitive.contentOrNull

        val taskList = (extra.info as TaskListInfo).getTaskList(extra.context) ?: run {
            Log.w(TAG, "Could not find task list ${extra.info.id}")
            return
        }
        val storedTask = taskList.findByUid(uid)
        when (ical) {
            null -> {
                if (storedTask == null) {
                    Log.i(TAG, "Unknown task $uid cannot be deleted")
                } else {
                    Log.d(TAG, "Delete task $uid")
                    storedTask.delete()
                    addToNumProcessedEntries(extra, -1)
                }
            }
            else -> {
                val tasks = try {
                    Task.tasksFromReader(StringReader(ical))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse iCalendar $ical", e)
                    return
                }
                if (tasks.isEmpty()) {
                    Log.w(TAG, "No tasks found in iCalendar $ical")
                    return
                }
                if (tasks.size > 1) {
                    Log.w(TAG, "Multiple tasks found in iCalendar $ical")
                    return
                }
                val task = tasks[0]
                if (storedTask == null) {
                    Log.d(TAG, "Add task $uid")
                    LocalTask(taskList, task).add()
                    addToNumProcessedEntries(extra, 1)
                } else {
                    Log.d(TAG, "Update task $uid")
                    storedTask.update(task)
                }
            }
        }
    }
}