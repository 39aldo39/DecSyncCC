package org.decsync.cc.contacts

import android.content.Context
import android.provider.ContactsContract
import androidx.work.WorkerParameters
import org.decsync.cc.CollectionInfo
import org.decsync.cc.InitWorker

@ExperimentalStdlibApi
class ContactsInitWorker(context: Context, params: WorkerParameters) : InitWorker(context, params) {
    override val type = CollectionInfo.Type.ADDRESS_BOOK
    override val authority = ContactsContract.AUTHORITY
}