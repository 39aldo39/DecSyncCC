package org.decsync.cc.calendars

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
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