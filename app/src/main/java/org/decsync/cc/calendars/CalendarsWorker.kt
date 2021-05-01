/**
 * DecSyncCC - CalendarsService.kt
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

import android.Manifest
import android.accounts.Account
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import androidx.work.*
import at.bitfire.ical4android.AndroidCalendar
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.*
import org.decsync.cc.R
import org.decsync.cc.calendars.CalendarDecsyncUtils.CalendarFactory
import org.decsync.cc.contacts.syncAdapterUri

class CalendarsWorker(context: Context, params: WorkerParameters) : CollectionWorker(context, params) {
    override val notificationTitleResId = R.string.notification_adding_events

    override fun getCollectionInfo(id: String, name: String): CollectionInfo {
        return CalendarInfo(id, name, null, false)
    }

    @ExperimentalStdlibApi
    override fun sync(info: CollectionInfo, provider: ContentProviderClient): Boolean {
        if (!PrefUtils.getUseSaf(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        PrefUtils.checkAppUpgrade(context)

        // Required for ical4android
        Thread.currentThread().contextClassLoader = context.classLoader

        // Allow custom event colors
        val account = info.getAccount(context)
        AndroidCalendar.insertColors(provider, account)

        val decsyncDir = PrefUtils.getNativeFile(context)
                ?: throw Exception(context.getString(R.string.settings_decsync_dir_not_configured))
        provider.query(syncAdapterUri(account, Calendars.CONTENT_URI),
                arrayOf(Calendars._ID, Calendars.CALENDAR_COLOR, COLUMN_OLD_COLOR),
                "${Calendars.NAME}=?", arrayOf(info.id), null)!!.use { calCursor ->
            if (calCursor.moveToFirst()) {
                val calendarId = calCursor.getLong(0)
                val color = calCursor.getInt(1)
                val oldColor = calCursor.getInt(2)

                val calendar = AndroidCalendar.findByID(account, provider, CalendarFactory, calendarId)
                val extra = Extra(info, context, provider)
                val decsync = getDecsync(info, context, decsyncDir)

                // Detect changed color
                if (color != oldColor) {
                    decsync.setEntry(listOf("info"), JsonPrimitive("color"), JsonPrimitive(Utils.colorToString(color)))

                    val values = ContentValues()
                    values.put(COLUMN_OLD_COLOR, color)
                    provider.update(syncAdapterUri(account, Calendars.CONTENT_URI),
                            values, "${Calendars._ID}=?", arrayOf(calendarId.toString()))
                }

                // Detect deleted events
                provider.query(syncAdapterUri(account, Events.CONTENT_URI), arrayOf(Events._ID),
                        "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=1",
                        arrayOf(calendarId.toString()), null)!!.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)

                        val values = ContentValues()
                        values.put(Events._ID, id)
                        LocalEvent(calendar, values).writeDeleteAction(decsync)
                        addToNumProcessedEntries(extra, -1)
                    }
                }

                // Detect dirty events
                provider.query(syncAdapterUri(account, Events.CONTENT_URI),
                        arrayOf(Events._ID, Events.ORIGINAL_ID, Events._SYNC_ID),
                        "${Events.CALENDAR_ID}=? AND ${Events.DIRTY}=1",
                        arrayOf(calendarId.toString()), null)!!.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(1) ?: cursor.getString(0)
                        val newEvent = cursor.isNull(1) && cursor.isNull(2)

                        val values = ContentValues()
                        values.put(Events._ID, id)
                        LocalEvent(calendar, values).writeUpdateAction(decsync)
                        if (newEvent) {
                            addToNumProcessedEntries(extra, 1)
                        }
                    }
                }

                decsync.executeAllNewEntries(extra)
                return true
            } else {
                return false
            }
        }
    }

    companion object {
        @ExperimentalStdlibApi
        fun enqueueAll(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val account = Account(PrefUtils.getCalendarAccountName(context), context.getString(R.string.account_type_calendars))
            val provider = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return
            try {
                provider.query(syncAdapterUri(account, Calendars.CONTENT_URI),
                        arrayOf(Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME),
                        null, null, null)!!.use { calCursor ->
                    while (calCursor.moveToNext()) {
                        val id = calCursor.getString(0)
                        val name = calCursor.getString(1)
                        val info = CalendarInfo(id, name, null, false)
                        enqueue(context, info)
                    }
                }
            } finally {
                if (Build.VERSION.SDK_INT >= 24)
                    provider.close()
                else
                    @Suppress("DEPRECATION")
                    provider.release()
            }
        }
    }
}
