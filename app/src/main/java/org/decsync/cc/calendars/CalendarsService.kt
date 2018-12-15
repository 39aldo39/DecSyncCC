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
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.support.v4.content.ContextCompat
import at.bitfire.ical4android.AndroidCalendar
import org.decsync.cc.CollectionInfo
import org.decsync.cc.Extra
import org.decsync.cc.calendars.CalendarDecsyncUtils.CalendarFactory
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.cc.getDecsync

class CalendarsService : Service() {

    private var mCalendarsSyncAdapter: CalendarsSyncAdapter? = null

    override fun onCreate() {
        super.onCreate()
        if (mCalendarsSyncAdapter == null) {
            mCalendarsSyncAdapter = CalendarsSyncAdapter(applicationContext, true)
        }
    }

    override fun onBind(intent: Intent): IBinder? = mCalendarsSyncAdapter?.syncAdapterBinder

    internal inner class CalendarsSyncAdapter(context: Context, autoInitialize: Boolean) : AbstractThreadedSyncAdapter(context, autoInitialize) {
        override fun onPerformSync(account: Account, extras: Bundle,
                                   authority: String, provider: ContentProviderClient,
                                   syncResult: SyncResult) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                syncResult.databaseError = true
                return
            }

            // Required for ical4android
            Thread.currentThread().contextClassLoader = context.classLoader

            provider.query(syncAdapterUri(account, Calendars.CONTENT_URI),
                    arrayOf(Calendars._ID, Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, COLUMN_OLD_COLOR),
                    null, null, null).use { calCursor ->
                while (calCursor.moveToNext()) {
                    val calendarId = calCursor.getLong(0)
                    val decsyncId = calCursor.getString(1)
                    val name = calCursor.getString(2)
                    val color = calCursor.getInt(3)
                    val oldColor = calCursor.getInt(4)

                    val calendar = AndroidCalendar.findByID(account, provider, CalendarFactory, calendarId)
                    val info = CollectionInfo(CollectionInfo.Type.CALENDAR, decsyncId, name, context)
                    val decsync = getDecsync(info)

                    // Detect changed color
                    if (color != oldColor) {
                        decsync.setEntry(listOf("info"), "color", String.format("#%06X", color and 0xFFFFFF))

                        val values = ContentValues()
                        values.put(COLUMN_OLD_COLOR, color)
                        provider.update(syncAdapterUri(account, Calendars.CONTENT_URI),
                                values, "${Calendars._ID}=?", arrayOf(calendarId.toString()))
                    }

                    // Detect deleted events
                    provider.query(syncAdapterUri(account, Events.CONTENT_URI), arrayOf(Events._ID),
                            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=1",
                            arrayOf(calendarId.toString()), null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)

                            val values = ContentValues()
                            values.put(Events._ID, id)
                            LocalEvent(calendar, values).writeDeleteAction(decsync)
                        }
                    }

                    // Detect dirty events
                    provider.query(syncAdapterUri(account, Events.CONTENT_URI),
                            arrayOf(Events._ID, Events.ORIGINAL_ID),
                            "${Events.CALENDAR_ID}=? AND ${Events.DIRTY}=1",
                            arrayOf(calendarId.toString()), null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(1) ?: cursor.getString(0)

                            val values = ContentValues()
                            values.put(Events._ID, id)
                            LocalEvent(calendar, values).writeUpdateAction(decsync)
                        }
                    }

                    decsync.executeAllNewEntries(Extra(info, context, provider))
                }
            }
        }
    }
}
