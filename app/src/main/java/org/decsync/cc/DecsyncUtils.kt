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

import android.content.ContentProviderClient
import android.content.Context
import org.decsync.cc.calendars.CalendarsListeners
import org.decsync.cc.contacts.ContactsListeners
import org.decsync.cc.tasks.TasksListeners
import org.decsync.library.*

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