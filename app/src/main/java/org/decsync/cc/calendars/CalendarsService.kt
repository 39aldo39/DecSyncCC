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
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import at.bitfire.ical4android.AndroidCalendar
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.cc.*
import org.decsync.cc.calendars.CalendarDecsyncUtils.CalendarFactory
import org.decsync.cc.contacts.syncAdapterUri

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
        @ExperimentalStdlibApi
        override fun onPerformSync(account: Account, extras: Bundle,
                                   authority: String, provider: ContentProviderClient,
                                   syncResult: SyncResult) {
            val success = sync(context, account, provider)
            if (!success) {
                syncResult.databaseError = true
            }
        }
    }

    companion object {
        @ExperimentalStdlibApi
        fun sync(context: Context, account: Account, provider: ContentProviderClient): Boolean {
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
            AndroidCalendar.insertColors(provider, account)

            try {
                val decsyncDir = PrefUtils.getNativeFile(context)
                        ?: throw Exception(context.getString(R.string.settings_decsync_dir_not_configured))
                provider.query(syncAdapterUri(account, Calendars.CONTENT_URI),
                        arrayOf(Calendars._ID, Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, COLUMN_OLD_COLOR),
                        null, null, null)!!.use { calCursor ->
                    while (calCursor.moveToNext()) {
                        val calendarId = calCursor.getLong(0)
                        val decsyncId = calCursor.getString(1)
                        val name = calCursor.getString(2)
                        val color = calCursor.getInt(3)
                        val oldColor = calCursor.getInt(4)

                        val calendar = AndroidCalendar.findByID(account, provider, CalendarFactory, calendarId)
                        val info = CalendarInfo(decsyncId, name, null)
                        val extra = Extra(info, context, provider)
                        val decsync = getDecsync(info, context, decsyncDir)

                        // Detect changed color
                        if (color != oldColor) {
                            decsync.setEntry(listOf("info"), JsonPrimitive("color"), JsonPrimitive(String.format("#%06X", color and 0xFFFFFF)))

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
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "", e)
                val builder = errorNotificationBuilder(context).apply {
                    setSmallIcon(R.drawable.ic_notification)
                    if (PrefUtils.getUpdateForcesSaf(context)) {
                        setContentTitle(context.getString(R.string.notification_saf_update_title))
                        setContentText(context.getString(R.string.notification_saf_update_message))
                    } else {
                        setContentTitle("DecSync")
                        setContentText(e.message)
                    }
                    setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), 0))
                    setAutoCancel(true)
                }
                with(NotificationManagerCompat.from(context)) {
                    notify(0, builder.build())
                }
                return false
            }

            return true
        }
    }
}

class CalendarsWorker(val context: Context, params: WorkerParameters) : Worker(context, params) {
    @ExperimentalStdlibApi
    override fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SYNC_STATS) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure()
        }

        val calendarsAccount = Account(PrefUtils.getCalendarAccountName(context), context.getString(R.string.account_type_calendars))
        if (ContentResolver.isSyncActive(calendarsAccount, CalendarContract.AUTHORITY)) {
            return Result.success()
        }

        val provider = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return Result.failure()
        try {
            val success = CalendarsService.sync(context, calendarsAccount, provider)
            return if (success) Result.success() else Result.failure()
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }
    }
}
