package org.decsync.cc.contacts

import android.content.Context
import android.provider.ContactsContract
import androidx.work.WorkerParameters
import org.decsync.cc.AddressBookInfo
import org.decsync.cc.CollectionInfo
import org.decsync.cc.InitWorker
import org.decsync.cc.R

@ExperimentalStdlibApi
class ContactsInitWorker(context: Context, params: WorkerParameters) : InitWorker(context, params) {
    override val notificationTitleResId = R.string.notification_adding_contacts

    override fun getCollectionInfo(id: String, name: String): CollectionInfo {
        return AddressBookInfo(id, name)
    }
}