package org.decsync.cc.calendars

import android.content.Context
import android.provider.CalendarContract
import androidx.work.WorkerParameters
import org.decsync.cc.CalendarInfo
import org.decsync.cc.CollectionInfo
import org.decsync.cc.InitWorker
import org.decsync.cc.R

@ExperimentalStdlibApi
class CalendarsInitWorker(context: Context, params: WorkerParameters) : InitWorker(context, params) {
    override val notificationTitleResId = R.string.notification_adding_events

    override fun getCollectionInfo(id: String, name: String): CollectionInfo {
        return CalendarInfo(id, name, null)
    }
}