/**
 * DecSyncCC - DecsyncUtils.kt
 *
 * Copyright (C) 2018 Aldo Gunsing
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package org.decsync.cc

import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.decsync.cc.calendars.CalendarsListeners
import org.decsync.cc.calendars.CalendarsUtils
import org.decsync.cc.contacts.ContactsListeners
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.cc.tasks.TasksListeners
import org.decsync.library.*

const val KEY_NUM_PROCESSED_ENTRIES = "num-processed-entries"

@ExperimentalStdlibApi
data class Extra(
        val info: CollectionInfo,
        val context: Context,
        val provider: ContentProviderClient
)

@ExperimentalStdlibApi
fun getDecsync(info: CollectionInfo, context: Context, decsyncDir: NativeFile): Decsync<Extra> {
    val ownAppId = PrefUtils.getOwnAppId(context)
    val decsync = Decsync<Extra>(decsyncDir, info.syncType, info.id, ownAppId)
    val infoListener = when (info) {
        is AddressBookInfo -> ContactsListeners::infoListener
        is CalendarInfo -> CalendarsListeners::infoListener
        is TaskListInfo -> TasksListeners::infoListener
    }
    decsync.addListener(listOf("info"), infoListener)
    val resourcesListener = when (info) {
        is AddressBookInfo -> ContactsListeners::resourcesListener
        is CalendarInfo -> CalendarsListeners::resourcesListener
        is TaskListInfo -> TasksListeners::resourcesListener
    }
    decsync.addListener(listOf("resources"), resourcesListener)
    return decsync
}

@ExperimentalStdlibApi
fun addToNumProcessedEntries(extra: Extra, add: Int) {
    val addFunction = when (extra.info) {
        is AddressBookInfo -> ::addToNumProcessedEntriesContacts
        is CalendarInfo -> ::addToNumProcessedEntriesCalendar
        is TaskListInfo -> ::addToNumProcessedEntriesTasks
    }
    addFunction(extra, add)
}

@ExperimentalStdlibApi
private fun addToNumProcessedEntriesContacts(extra: Extra, add: Int) {
    val accountManager = AccountManager.get(extra.context)
    val account = extra.info.getAccount(extra.context)
    val count = (accountManager.getUserData(account, KEY_NUM_PROCESSED_ENTRIES) ?: return).toInt()
    accountManager.setUserData(account, KEY_NUM_PROCESSED_ENTRIES, (count + add).toString())
}

@ExperimentalStdlibApi
private fun addToNumProcessedEntriesCalendar(extra: Extra, add: Int) {
    val account = extra.info.getAccount(extra.context)
    val count = extra.provider.query(syncAdapterUri(account, CalendarContract.Calendars.CONTENT_URI),
            arrayOf(CalendarsUtils.COLUMN_NUM_PROCESSED_ENTRIES), "${CalendarContract.Calendars.NAME}=?",
            arrayOf(extra.info.id), null)!!.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
    } ?: return
    val values = ContentValues()
    values.put(CalendarsUtils.COLUMN_NUM_PROCESSED_ENTRIES, count + add)
    extra.provider.update(syncAdapterUri(account, CalendarContract.Calendars.CONTENT_URI),
            values, "${CalendarContract.Calendars.NAME}=?", arrayOf(extra.info.id))
}

@ExperimentalStdlibApi
private fun addToNumProcessedEntriesTasks(extra: Extra, add: Int) {
    val info = extra.info as TaskListInfo
    val taskList = info.getTaskList(extra.context) ?: return
    taskList.numProcessedEntries += add
}

@ExperimentalStdlibApi
fun setNumProcessedEntries(extra: Extra, count: Int) {
    val setFunction = when (extra.info) {
        is AddressBookInfo -> ::setNumProcessedEntriesContacts
        is CalendarInfo -> ::setNumProcessedEntriesCalendar
        is TaskListInfo -> ::setNumProcessedEntriesTasks
    }
    setFunction(extra, count)
}

@ExperimentalStdlibApi
private fun setNumProcessedEntriesContacts(extra: Extra, count: Int) {
    val accountManager = AccountManager.get(extra.context)
    val account = extra.info.getAccount(extra.context)
    accountManager.setUserData(account, KEY_NUM_PROCESSED_ENTRIES, count.toString())
}

@ExperimentalStdlibApi
private fun setNumProcessedEntriesCalendar(extra: Extra, count: Int) {
    val account = extra.info.getAccount(extra.context)
    val values = ContentValues()
    values.put(CalendarsUtils.COLUMN_NUM_PROCESSED_ENTRIES, count)
    extra.provider.update(syncAdapterUri(account, CalendarContract.Calendars.CONTENT_URI),
            values, "${CalendarContract.Calendars.NAME}=?", arrayOf(extra.info.id))
}

@ExperimentalStdlibApi
private fun setNumProcessedEntriesTasks(extra: Extra, count: Int) {
    val info = extra.info as TaskListInfo
    val taskList = info.getTaskList(extra.context) ?: return
    taskList.numProcessedEntries = count
}