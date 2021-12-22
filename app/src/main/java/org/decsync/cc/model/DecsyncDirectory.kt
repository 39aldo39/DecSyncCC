package org.decsync.cc.model

import android.accounts.Account
import android.content.Context
import android.net.Uri
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import org.decsync.cc.PrefUtils
import org.decsync.cc.R
import org.decsync.library.NativeFile
import org.decsync.library.checkDecsyncInfo
import org.decsync.library.nativeFileFromDirUri
import org.decsync.library.nativeFileFromFile
import java.io.File

@Entity(tableName = "decsync_directories", indices = [
    Index(value = ["id"], unique = true),
    Index(value = ["directory"], unique = true),
    Index(value = ["name"], unique = true),
    Index(value = ["calendarAccountName"], unique = true),
    Index(value = ["taskListAccountName"], unique = true)
])
data class DecsyncDirectory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val directory: String,
    val name: String,
    val contactsFormatAccountName: String,
    val calendarAccountName: String,
    val taskListAccountName: String
) {
    @Ignore
    lateinit var nativeFile: NativeFile

    @ExperimentalStdlibApi
    fun getNativeFile(context: Context): NativeFile {
        return if (this::nativeFile.isInitialized) {
            nativeFile
        } else {
            if (PrefUtils.getUseSaf(context)) {
                nativeFileFromDirUri(context, Uri.parse(directory))
            } else {
                nativeFileFromFile(File(directory))
            }.also {
                checkDecsyncInfo(it)
                nativeFile = it
            }
        }
    }

    fun getContactsAccount(context: Context, name: String): Account {
        return Account(contactsFormatAccountName.format(name), context.getString(R.string.account_type_contacts))
    }

    fun getCalendarAccount(context: Context): Account {
        return Account(calendarAccountName, context.getString(R.string.account_type_calendars))
    }

    fun getTaskListAccount(context: Context): Account {
        return Account(taskListAccountName, context.getString(R.string.account_type_tasks))
    }
}