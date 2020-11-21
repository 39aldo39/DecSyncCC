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
import org.decsync.cc.calendars.COLUMN_NUM_PROCESSED_ENTRIES
import org.decsync.cc.calendars.CalendarDecsyncUtils
import org.decsync.cc.contacts.ContactDecsyncUtils
import org.decsync.cc.contacts.KEY_NUM_PROCESSED_ENTRIES
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.*

@ExperimentalStdlibApi
class Extra(
        val info: CollectionInfo,
        val context: Context,
        val provider: ContentProviderClient
)

@ExperimentalStdlibApi
fun getDecsync(info: CollectionInfo): Decsync<Extra> {
    val decsync = Decsync<Extra>(info.decsyncDir, info.syncType, info.collection, info.appId)
    val infoListener = when (info.type) {
        CollectionInfo.Type.ADDRESS_BOOK -> ContactDecsyncUtils::infoListener
        CollectionInfo.Type.CALENDAR -> CalendarDecsyncUtils::infoListener
    }
    decsync.addListener(listOf("info"), infoListener)
    val resourcesListener = when (info.type) {
        CollectionInfo.Type.ADDRESS_BOOK -> ContactDecsyncUtils::resourcesListener
        CollectionInfo.Type.CALENDAR -> CalendarDecsyncUtils::resourcesListener
    }
    decsync.addListener(listOf("resources"), resourcesListener)
    return decsync
}

@ExperimentalStdlibApi
fun addToNumProcessedEntries(extra: Extra, add: Int) {
    when (extra.info.type) {
        CollectionInfo.Type.ADDRESS_BOOK -> addToNumProcessedEntriesContacts(extra, add)
        CollectionInfo.Type.CALENDAR -> addToNumProcessedEntriesCalendar(extra, add)
    }
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
            arrayOf(COLUMN_NUM_PROCESSED_ENTRIES), "${CalendarContract.Calendars.NAME}=?",
            arrayOf(extra.info.id), null)!!.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
    } ?: return
    val values = ContentValues()
    values.put(COLUMN_NUM_PROCESSED_ENTRIES, count + add)
    extra.provider.update(syncAdapterUri(account, CalendarContract.Calendars.CONTENT_URI),
            values, "${CalendarContract.Calendars.NAME}=?", arrayOf(extra.info.id))
}

@ExperimentalStdlibApi
fun setNumProcessedEntries(extra: Extra, count: Int) {
    when (extra.info.type) {
        CollectionInfo.Type.ADDRESS_BOOK -> setNumProcessedEntriesContacts(extra, count)
        CollectionInfo.Type.CALENDAR -> setNumProcessedEntriesCalendar(extra, count)
    }
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
    values.put(COLUMN_NUM_PROCESSED_ENTRIES, count)
    extra.provider.update(syncAdapterUri(account, CalendarContract.Calendars.CONTENT_URI),
            values, "${CalendarContract.Calendars.NAME}=?", arrayOf(extra.info.id))
}