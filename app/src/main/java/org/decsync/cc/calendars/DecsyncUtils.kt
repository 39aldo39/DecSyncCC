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
import org.decsync.cc.Extra
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.Decsync
import org.decsync.library.OnSubdirEntryUpdateListener
import org.decsync.library.OnSubfileEntryUpdateListener
import org.json.JSONObject
import java.io.StringReader

const val TAG = "DecSync Calendars"
const val COLUMN_OLD_COLOR = Calendars.CAL_SYNC1

object CalendarDecsyncUtils {
    class InfoListener : OnSubfileEntryUpdateListener<Extra> {
        override val subfile = listOf("info")

        override fun onSubfileEntryUpdate(entry: Decsync.Entry, extra: Extra) {
            Log.d(TAG, "Execute info entry $entry")
            val info = entry.key
            val account = extra.info.getAccount(extra.context)
            when (info) {
                "deleted" -> {
                    val deleted = entry.value
                    if (deleted !is Boolean) {
                        Log.w(TAG, "Invalid 'deleted' value $deleted")
                        return
                    }
                    if (!deleted) {
                        return
                    }
                    Log.d(TAG, "Delete calendar ${extra.info.name}")

                    extra.provider.delete(syncAdapterUri(account, Calendars.CONTENT_URI),
                            "${Calendars.NAME}=?", arrayOf(extra.info.id))
                }
                "name" -> {
                    val name = entry.value
                    if (name !is String) {
                        Log.w(TAG, "Invalid name $name")
                        return
                    }
                    Log.d(TAG, "Rename calendar ${extra.info.name} to $name")

                    val values = ContentValues()
                    values.put(Calendars.CALENDAR_DISPLAY_NAME, name)
                    extra.provider.update(syncAdapterUri(account, Calendars.CONTENT_URI),
                            values, "${Calendars.NAME}=?", arrayOf(extra.info.id))
                }
                "color" -> {
                    val values = ContentValues()
                    val success = addColor(values, entry.value)
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
    }

    fun addColor(values: ContentValues, value: Any): Boolean {
        if (value !is String) {
            Log.w(TAG, "Invalid color $value")
            return false
        }
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

    class ResourcesListener : OnSubdirEntryUpdateListener<Extra> {
        override val subdir = listOf("resources")

        override fun onSubdirEntryUpdate(path: List<String>, entry: Decsync.Entry, extra: Extra) {
            if (path.size != 1) {
                Log.w(TAG, "Invalid path $path")
                return
            }
            val uid = path[0]
            if (entry.key != JSONObject.NULL) {
                Log.w(TAG, "Invalid key ${entry.key}")
                return
            }
            val ical = entry.value

            val account = extra.info.getAccount(extra.context)
            val calendarId = extra.provider.query(syncAdapterUri(account, Calendars.CONTENT_URI), arrayOf(Calendars._ID),
                    "${Calendars.NAME}=?", arrayOf(extra.info.id), null).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    Log.w(TAG, "Unknown calendar ${extra.info.id}")
                    return
                }
            }
            val calendar = AndroidCalendar.findByID(account, extra.provider, CalendarFactory, calendarId)
            val id = extra.provider.query(syncAdapterUri(account, Events.CONTENT_URI), arrayOf(Events._ID),
                    "${Events._SYNC_ID}=?", arrayOf(uid), null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            when (ical) {
                JSONObject.NULL -> {
                    if (id == null) {
                        Log.w(TAG, "Unknown event $uid cannot be deleted")
                    } else {
                        Log.d(TAG, "Delete event $uid")
                        val values = ContentValues()
                        values.put(Events._ID, id)
                        LocalEvent(calendar, values).delete()
                    }
                }
                is String -> {
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
                    } else {
                        Log.i(TAG, "Update event $uid")
                        val values = ContentValues()
                        values.put(Events._ID, id)
                        LocalEvent(calendar, values).update(event)
                    }
                }
                else -> {
                    Log.w(TAG, "Invalid iCalendar $ical")
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
