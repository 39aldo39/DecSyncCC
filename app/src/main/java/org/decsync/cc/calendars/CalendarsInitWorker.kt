package org.decsync.cc.calendars

import android.content.Context
import android.provider.CalendarContract
import androidx.work.WorkerParameters
import org.decsync.cc.CollectionInfo
import org.decsync.cc.InitWorker

@ExperimentalStdlibApi
class CalendarsInitWorker(context: Context, params: WorkerParameters) : InitWorker(context, params) {
    override val type = CollectionInfo.Type.CALENDAR
    override val authority = CalendarContract.AUTHORITY
}