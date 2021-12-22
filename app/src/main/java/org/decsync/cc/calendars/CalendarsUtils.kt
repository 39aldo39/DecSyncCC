package org.decsync.cc.calendars

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.CalendarContract
import at.bitfire.ical4android.AndroidCalendar
import at.bitfire.ical4android.AndroidCalendarFactory
import at.bitfire.ical4android.AndroidEvent

@ExperimentalStdlibApi
object CalendarsUtils {
    const val COLUMN_OLD_COLOR = CalendarContract.Calendars.CAL_SYNC1
    const val COLUMN_NUM_PROCESSED_ENTRIES = CalendarContract.Calendars.CAL_SYNC2

    fun addColor(values: ContentValues, color: Int) {
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, color)
        values.put(COLUMN_OLD_COLOR, color)
    }

    fun listCalendars(context: Context): List<Pair<Long, Triple<String, String, Int>>> {
        val calendars = mutableListOf<Triple<Long, String, String>>()
        val calendarsCountMap = mutableMapOf<Long, Triple<String, String, Int>>()
        val provider = context.contentResolver.acquireContentProviderClient(CalendarContract.CONTENT_URI)!!
        try {
            provider.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                null, null, null
            )!!.use { cursor ->
                while (cursor.moveToNext()) {
                    val calendarId = cursor.getLong(0)
                    val accountName = cursor.getString(1)
                    val displayName = cursor.getString(2)

                    calendars.add(Triple(calendarId, accountName, displayName))
                }
            }
            for ((calendarId, accountName, displayName) in calendars) {
                provider.query(CalendarContract.Events.CONTENT_URI, arrayOf(),
                    "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.ORIGINAL_ID} IS NULL",
                    arrayOf(calendarId.toString()), null)!!.use { cursor ->
                    calendarsCountMap.put(calendarId, Triple(accountName, displayName, cursor.count))
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }
        return calendarsCountMap.toList()
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