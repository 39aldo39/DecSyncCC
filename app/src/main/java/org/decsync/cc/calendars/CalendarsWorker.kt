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
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import androidx.work.*
import at.bitfire.ical4android.AndroidCalendar
import at.bitfire.ical4android.Event
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.*
import org.decsync.cc.R
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.library.Decsync
import org.decsync.library.NativeFile

@ExperimentalStdlibApi
typealias CalendarItem = Triple<Long, String, Event>

@ExperimentalStdlibApi
class CalendarsWorker(context: Context, params: WorkerParameters) : CollectionWorker<CalendarItem>(context, params) {
    override val initSyncNotificationTitleResId = R.string.notification_adding_events

    override fun getCollectionInfo(decsyncDir: DecsyncDirectory, id: String, name: String): CollectionInfo {
        return CalendarInfo(decsyncDir, id, name, null, false)
    }

    override suspend fun sync(info: CollectionInfo, provider: ContentProviderClient, nativeFile: NativeFile): Boolean {
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

        provider.query(syncAdapterUri(account, Calendars.CONTENT_URI),
                arrayOf(Calendars._ID, Calendars.CALENDAR_COLOR, CalendarsUtils.COLUMN_OLD_COLOR),
                "${Calendars.NAME}=?", arrayOf(info.id), null)!!.use { calCursor ->
            if (calCursor.moveToFirst()) {
                val calendarId = calCursor.getLong(0)
                val color = calCursor.getInt(1)
                val oldColor = calCursor.getInt(2)

                val calendar = AndroidCalendar.findByID(account, provider, CalendarsUtils.CalendarFactory, calendarId)
                val extra = Extra(info, context, provider)
                val decsync = getDecsync(info, context, nativeFile)

                // Detect changed color
                if (color != oldColor) {
                    decsync.setEntry(listOf("info"), JsonPrimitive("color"), JsonPrimitive(Utils.colorToString(color)))

                    val values = ContentValues()
                    values.put(CalendarsUtils.COLUMN_OLD_COLOR, color)
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

    override val importNotificationTitleResId = R.string.notification_importing_events

    override fun getItems(provider: ContentProviderClient, info: CollectionInfo): List<CalendarItem> {
        val calendarId = PrefUtils.getImportedCalendarId(context, info)
        val calendar = AndroidCalendar.findByID(info.getAccount(context), provider, CalendarsUtils.CalendarFactory, calendarId)
        val events = mutableListOf<Triple<Long, String, Event>>()
        provider.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID} IS NULL AND ${Events.DELETED}=0",
            arrayOf(calendarId.toString()), null)!!.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)

                val values = ContentValues()
                values.put(Events._ID, id)
                val (uid, event) = LocalEvent(calendar, values).getUidAndEvent()
                events.add(Triple(calendarId, uid, event))
            }
        }
        return events
    }

    override fun writeItemDecsync(decsync: Decsync<Extra>, item: CalendarItem) {
        val (_, uid, event) = item
        LocalEvent.writeDecsyncEvent(decsync, uid, event)
    }

    override fun writeItemAndroid(info: CollectionInfo, provider: ContentProviderClient, item: CalendarItem) {
        val (calendarId, _, event) = item
        val calendar = AndroidCalendar.findByID(info.getAccount(context), provider, CalendarsUtils.CalendarFactory, calendarId)
        LocalEvent(calendar, event).add()
    }

    companion object {
        suspend fun enqueueAll(context: Context) {
            val decsyncDirs = App.db.decsyncDirectoryDao().all()
            for (decsyncDir in decsyncDirs) {
                enqueueDir(context, decsyncDir)
            }
        }

        fun enqueueDir(context: Context, decsyncDir: DecsyncDirectory) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val provider = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return
            try {
                val account = decsyncDir.getCalendarAccount(context)
                provider.query(syncAdapterUri(account, Calendars.CONTENT_URI),
                    arrayOf(Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME),
                    null, null, null)!!.use { calCursor ->
                    while (calCursor.moveToNext()) {
                        val id = calCursor.getString(0)
                        val name = calCursor.getString(1)

                        val info = CalendarInfo(decsyncDir, id, name, null, false)
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
