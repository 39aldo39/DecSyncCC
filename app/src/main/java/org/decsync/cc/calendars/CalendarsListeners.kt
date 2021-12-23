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

import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import at.bitfire.ical4android.*
import kotlinx.serialization.json.*
import org.decsync.cc.Extra
import org.decsync.cc.Utils
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.Decsync
import java.io.StringReader

private const val TAG = "DecSync Calendars"

@ExperimentalStdlibApi
object CalendarsListeners {
    fun infoListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute info entry $entry")
        val info = entry.key.jsonPrimitive.content
        val account = extra.info.getAccount(extra.context)
        when (info) {
            "deleted" -> {
                val deleted = entry.value.jsonPrimitive.boolean
                if (!deleted) {
                    return
                }
                Log.d(TAG, "Delete calendar ${extra.info.name}")

                extra.provider.delete(syncAdapterUri(account, Calendars.CONTENT_URI),
                        "${Calendars.NAME}=?", arrayOf(extra.info.id))
            }
            "name" -> {
                val name = entry.value.jsonPrimitive.content
                if (extra.info.name == name) return
                Log.d(TAG, "Rename calendar ${extra.info.name} to $name")

                val values = ContentValues()
                values.put(Calendars.CALENDAR_DISPLAY_NAME, name)
                extra.provider.update(syncAdapterUri(account, Calendars.CONTENT_URI),
                        values, "${Calendars.NAME}=?", arrayOf(extra.info.id))
            }
            "color" -> {
                val decsyncColor = entry.value.jsonPrimitive.content
                val color = Utils.parseColor(decsyncColor) ?: return
                val values = ContentValues()
                CalendarsUtils.addColor(values, color)

                Log.d(TAG, "Set color of calendar ${extra.info.name} to ${entry.value}")
                extra.provider.update(syncAdapterUri(account, Calendars.CONTENT_URI),
                        values, "${Calendars.NAME}=?", arrayOf(extra.info.id))
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
        val calendar = AndroidCalendar.findByID(account, extra.provider, CalendarsUtils.CalendarFactory, calendarId)
        val id = extra.provider.query(syncAdapterUri(account, Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events._SYNC_ID}=?", arrayOf(uid), null)!!.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
        when (ical) {
            null -> {
                if (id == null) {
                    Log.i(TAG, "Unknown event $uid cannot be deleted")
                } else {
                    Log.d(TAG, "Delete event $uid")
                    val values = ContentValues()
                    values.put(Events._ID, id)
                    LocalEvent(calendar, values).delete()
                }
            }
            else -> {
                val events = try {
                    Event.eventsFromReader(StringReader(ical))
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
                } else {
                    Log.d(TAG, "Update event $uid")
                    val values = ContentValues()
                    values.put(Events._ID, id)
                    LocalEvent(calendar, values).update(event)
                }
            }
        }
    }
}
