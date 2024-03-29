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

import android.content.ContentValues
import android.provider.CalendarContract.Events
import at.bitfire.ical4android.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.Extra
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.Decsync
import java.io.ByteArrayOutputStream
import java.util.*

@ExperimentalStdlibApi
class LocalEvent: AndroidEvent {

    companion object {
        fun writeDecsyncEvent(decsync: Decsync<Extra>, uid: String, event: Event) {
            val os = ByteArrayOutputStream()
            event.write(os)
            val ical = os.toString("UTF-8")
            decsync.setEntry(listOf("resources", uid), JsonNull, JsonPrimitive(ical))
        }
    }

    constructor(calendar: AndroidCalendar<*>, event: Event) : super(calendar, event)
    constructor(calendar: AndroidCalendar<*>, values: ContentValues) : super(calendar, values)

    fun getUidAndEvent(): Pair<String, Event> {
        val event = requireNotNull(event)
        val uid = event.uid ?: UUID.randomUUID().toString()
        event.uid = uid
        return Pair(uid, event)
    }

    fun writeDeleteAction(decsync: Decsync<Extra>) {
        val event = requireNotNull(event)
        val uid = event.uid

        if (uid != null) {
            decsync.setEntry(listOf("resources", uid), JsonNull, JsonNull)
        }

        super.delete()
    }

    fun writeUpdateAction(decsync: Decsync<Extra>) {
        val (uid, event) = getUidAndEvent()
        writeDecsyncEvent(decsync, uid, event)

        val values = ContentValues()
        values.put(Events.UID_2445, uid)
        values.put(Events.DIRTY, 0)
        calendar.provider.update(syncAdapterUri(calendar.account, Events.CONTENT_URI),
                values, "${Events.ORIGINAL_ID}=?", arrayOf(id.toString()))
        values.put(Events._SYNC_ID, uid)
        calendar.provider.update(eventSyncURI(), values, null, null)
    }

    override fun populateEvent(row: ContentValues, groupScheduled: Boolean) {
        super.populateEvent(row, groupScheduled)
        val event = requireNotNull(event)

        event.uid = row.getAsString(Events.UID_2445)
    }

    override fun buildEvent(recurrence: Event?, builder: BatchOperation.CpoBuilder) {
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
