/**
 * DecSyncCC - ContactsService.kt
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

package org.decsync.cc.contacts

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import androidx.core.content.ContextCompat
import androidx.work.*
import at.bitfire.vcard4android.AndroidAddressBook
import org.decsync.cc.*
import org.decsync.cc.R
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.library.NativeFile

fun syncAdapterUri(account: Account, uri: Uri): Uri {
    return uri.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()
}

@ExperimentalStdlibApi
class ContactsWorker(context: Context, params: WorkerParameters) : CollectionWorker(context, params) {
    override val notificationTitleResId = R.string.notification_adding_contacts

    override fun getCollectionInfo(decsyncDir: DecsyncDirectory, id: String, name: String): CollectionInfo {
        return AddressBookInfo(decsyncDir, id, name, false)
    }

    override suspend fun sync(info: CollectionInfo, provider: ContentProviderClient, nativeFile: NativeFile): Boolean {
        if (!PrefUtils.getUseSaf(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        PrefUtils.checkAppUpgrade(context)

        val extra = Extra(info, context, provider)
        val decsync = getDecsync(info, context, nativeFile)
        val account = info.getAccount(context)
        val addressBook = AndroidAddressBook(account, provider, LocalContact.ContactFactory, LocalContact.GroupFactory)

        // Detect deleted contacts
        provider.query(syncAdapterUri(account, RawContacts.CONTENT_URI),
                arrayOf(RawContacts._ID, LocalContact.COLUMN_LOCAL_UID),
                "${RawContacts.DELETED}=1", null, null
        )!!.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val uid = cursor.getString(1)

                val values = ContentValues()
                values.put(RawContacts._ID, id)
                values.put(LocalContact.COLUMN_LOCAL_UID, uid)
                LocalContact(addressBook, values).writeDeleteAction(decsync)
                addToNumProcessedEntries(extra, -1)
            }
        }

        // Detect dirty contacts
        provider.query(syncAdapterUri(account, RawContacts.CONTENT_URI),
                arrayOf(RawContacts._ID, LocalContact.COLUMN_LOCAL_UID, RawContacts.SOURCE_ID),
                "${RawContacts.DIRTY}=1", null, null)!!.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val uid = cursor.getString(1)
                val newContact = cursor.isNull(2)

                val values = ContentValues()
                values.put(RawContacts._ID, id)
                values.put(LocalContact.COLUMN_LOCAL_UID, uid)
                values.put(LocalContact.COLUMN_LOCAL_BOOKID, info.id)
                LocalContact(addressBook, values).writeUpdateAction(decsync)
                if (newContact) {
                    addToNumProcessedEntries(extra, 1)
                }
            }
        }

        decsync.executeAllNewEntries(extra)
        return true
    }

    companion object {
        suspend fun enqueueAll(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val accountManager = AccountManager.get(context)
            val accounts = accountManager.getAccountsByType(context.getString(R.string.account_type_contacts))
            for (account in accounts) {
                val dirId = accountManager.getUserData(account, AddressBookInfo.KEY_DECSYNC_DIR_ID).toLong()
                val decsyncDir = App.db.decsyncDirectoryDao().find(dirId) ?: continue
                val id = accountManager.getUserData(account, AddressBookInfo.KEY_COLLECTION_ID)
                val name = accountManager.getUserData(account, AddressBookInfo.KEY_NAME)
                val info = AddressBookInfo(decsyncDir, id, name, false)
                enqueue(context, info)
            }
        }
    }
}
