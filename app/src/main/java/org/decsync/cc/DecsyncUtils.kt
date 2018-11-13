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
import org.decsync.cc.calendars.CalendarDecsyncUtils
import org.decsync.cc.contacts.ContactDecsyncUtils
import org.decsync.library.Decsync
import org.decsync.library.getAppId

val ownAppId = getAppId("DecSyncCC")

fun getDecsync(info: CollectionInfo): Decsync<Extra> {
    val listeners = when (info.type) {
        CollectionInfo.Type.ADDRESS_BOOK -> listOf(
                (ContactDecsyncUtils::InfoListener)(),
                (ContactDecsyncUtils::ResourcesListener)()
        )
        CollectionInfo.Type.CALENDAR -> listOf(
                (CalendarDecsyncUtils::InfoListener)(),
                (CalendarDecsyncUtils::ResourcesListener)()
        )
    }
    return Decsync(info.dir, ownAppId, listeners)
}

class Extra(
        val info: CollectionInfo,
        val context: Context,
        val provider: ContentProviderClient
)
