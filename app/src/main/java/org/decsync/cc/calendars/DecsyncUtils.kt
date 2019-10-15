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

package org.decsync.cc.calendars

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.graphics.Color
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import at.bitfire.ical4android.*
import kotlinx.serialization.json.*
import org.decsync.cc.Extra
import org.decsync.cc.addToNumProcessedEntries
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.Decsync
import java.io.StringReader

const val TAG = "DecSync Calendars"
const val COLUMN_OLD_COLOR = Calendars.CAL_SYNC1
const val COLUMN_NUM_PROCESSED_ENTRIES = Calendars.CAL_SYNC2

@ExperimentalStdlibApi
object CalendarDecsyncUtils {
    fun infoListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute info entry $entry")
        val info = entry.key.content
        val account = extra.info.getAccount(extra.context)
        when (info) {
            "deleted" -> {
                val deleted = entry.value.boolean
                if (!deleted) {
                    return
                }
                Log.d(TAG, "Delete calendar ${extra.info.name}")

                extra.provider.delete(syncAdapterUri(account, Calendars.CONTENT_URI),
                        "${Calendars.NAME}=?", arrayOf(extra.info.id))
            }
            "name" -> {
                val name = entry.value.content
                Log.d(TAG, "Rename calendar ${extra.info.name} to $name")

                val values = ContentValues()
                values.put(Calendars.CALENDAR_DISPLAY_NAME, name)
                extra.provider.update(syncAdapterUri(account, Calendars.CONTENT_URI),
                        values, "${Calendars.NAME}=?", arrayOf(extra.info.id))
            }
            "color" -> {
                val color = entry.value.content
                val values = ContentValues()
                val success = addColor(values, color)
                if (!success) return

                Log.d(TAG, "Set color of calendar ${extra.info.name} to ${entry.value}")
                extra.provider.update(syncAdapterUri(account, Calendars.CONTENT_URI),
                        values, "${Calendars.NAME}=?", arrayOf(extra.info.id))
            }
            else -> {
                Log.w(TAG, "Unknown info key $info")
            }
        }
    }

    fun addColor(values: ContentValues, value: String): Boolean {
        return try {
            val color = Color.parseColor(value)
            values.put(Calendars.CALENDAR_COLOR, color)
            values.put(COLUMN_OLD_COLOR, color)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown color $value", e)
            false
        }
    }

    fun resourcesListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        if (path.size != 1) {
            Log.w(TAG, "Invalid path $path")
            return
        }
        val uid = path[0]
        val ical = entry.value.contentOrNull

        val account = extra.info.getAccount(extra.context)
        val calendarId = extra.provider.query(syncAdapterUri(account, Calendars.CONTENT_URI), arrayOf(Calendars._ID),
                "${Calendars.NAME}=?", arrayOf(extra.info.id), null)!!.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else {
                Log.w(TAG, "Unknown calendar ${extra.info.id}")
                return
            }
        }
        val calendar = AndroidCalendar.findByID(account, extra.provider, CalendarFactory, calendarId)
        val id = extra.provider.query(syncAdapterUri(account, Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events._SYNC_ID}=?", arrayOf(uid), null)!!.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
        when (ical) {
            null -> {
                if (id == null) {
                    Log.w(TAG, "Unknown event $uid cannot be deleted")
                } else {
                    Log.d(TAG, "Delete event $uid")
                    val values = ContentValues()
                    values.put(Events._ID, id)
                    LocalEvent(calendar, values).delete()
                    addToNumProcessedEntries(extra, -1)
                }
            }
            else -> {
                val events = try {
                    Event.fromReader(StringReader(ical))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse iCalendar $ical", e)
                    return
                }
                if (events.isEmpty()) {
                    Log.w(TAG, "No events found in iCalendar $ical")
                    return
                }
                if (events.size > 1) {
                    Log.w(TAG, "Multiple events found in iCalendar $ical")
                    return
                }
                val event = events[0]
                if (id == null) {
                    Log.d(TAG, "Add event $uid")
                    LocalEvent(calendar, event).add()
                    addToNumProcessedEntries(extra, 1)
                } else {
                    Log.i(TAG, "Update event $uid")
                    val values = ContentValues()
                    values.put(Events._ID, id)
                    LocalEvent(calendar, values).update(event)
                }
            }
        }
    }

    object CalendarFactory: AndroidCalendarFactory<CalendarFactory.LocalCalendar> {

        class LocalCalendar(
                account: Account,
                provider: ContentProviderClient,
                id: Long
        ): AndroidCalendar<AndroidEvent>(account, provider, LocalEvent.EventFactory, id)

        override fun newInstance(account: Account, provider: ContentProviderClient, id: Long) =
                LocalCalendar(account, provider, id)

    }
}
