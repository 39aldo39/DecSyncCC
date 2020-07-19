/**
 * DecSyncCC - LocalEvent.kt
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

package org.decsync.cc.calendars

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.provider.CalendarContract.Events
import at.bitfire.ical4android.AndroidCalendar
import at.bitfire.ical4android.AndroidEvent
import at.bitfire.ical4android.AndroidEventFactory
import at.bitfire.ical4android.Event
import kotlinx.serialization.json.JsonLiteral
import kotlinx.serialization.json.JsonNull
import org.decsync.cc.Extra
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.Decsync
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*

@ExperimentalStdlibApi
class LocalEvent: AndroidEvent {
    constructor(calendar: AndroidCalendar<*>, event: Event) : super(calendar, event)
    constructor(calendar: AndroidCalendar<*>, values: ContentValues) : super(calendar, values)

    fun writeDeleteAction(decsync: Decsync<Extra>) {
        val event = requireNotNull(event)
        val uid = event.uid

        if (uid != null) {
            decsync.setEntry(listOf("resources", uid), JsonNull, JsonNull)
        }

        super.delete()
    }

    fun writeUpdateAction(decsync: Decsync<Extra>) {
        val event = requireNotNull(event)
        val uid = event.uid ?: UUID.randomUUID().toString()
        event.uid = uid

        val values = ContentValues()
        values.put(Events.UID_2445, uid)
        values.put(Events.DIRTY, 0)
        calendar.provider.update(syncAdapterUri(calendar.account, Events.CONTENT_URI),
                values, "${Events.ORIGINAL_ID}=?", arrayOf(id.toString()))
        values.put(Events._SYNC_ID, uid)
        calendar.provider.update(eventSyncURI(), values, null, null)

        val os = ByteArrayOutputStream()
        event.write(os)
        val ical = os.toString("UTF-8")
        decsync.setEntry(listOf("resources", uid), JsonNull, JsonLiteral(ical))
    }

    override fun populateEvent(row: ContentValues, groupScheduled: Boolean) {
        super.populateEvent(row, groupScheduled)
        val event = requireNotNull(event)

        event.uid = row.getAsString(Events.UID_2445)
    }

    override fun buildEvent(recurrence: Event?, builder: ContentProviderOperation.Builder) {
        super.buildEvent(recurrence, builder)
        val event = requireNotNull(event)

        val syncIdColumn = if (recurrence == null) Events._SYNC_ID else Events.ORIGINAL_SYNC_ID
        builder .withValue(syncIdColumn, event.uid)
                .withValue(Events.UID_2445, event.uid)
                .withValue(Events.DIRTY, 0)
                .withValue(Events.DELETED, 0)
    }

    object EventFactory : AndroidEventFactory<LocalEvent> {
        override fun fromProvider(calendar: AndroidCalendar<AndroidEvent>, values: ContentValues) =
                LocalEvent(calendar, values)
    }
}
