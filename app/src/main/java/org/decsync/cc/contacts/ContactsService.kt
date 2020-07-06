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
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import androidx.core.content.ContextCompat
import at.bitfire.vcard4android.AndroidAddressBook
import org.decsync.cc.*

class ContactsService : Service() {

    private var mContactsSyncAdapter: ContactsSyncAdapter? = null

    override fun onCreate() {
        super.onCreate()
        if (mContactsSyncAdapter == null) {
            mContactsSyncAdapter = ContactsSyncAdapter(applicationContext, true)
        }
    }

    override fun onBind(intent: Intent): IBinder? = mContactsSyncAdapter?.syncAdapterBinder

    internal inner class ContactsSyncAdapter(context: Context, autoInitialize: Boolean) : AbstractThreadedSyncAdapter(context, autoInitialize) {
        @ExperimentalStdlibApi
        override fun onPerformSync(account: Account, extras: Bundle,
                                   authority: String, provider: ContentProviderClient,
                                   syncResult: SyncResult) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                syncResult.databaseError = true
                return
            }

            PrefUtils.checkAppUpgrade(context)

            val bookId = AccountManager.get(context).getUserData(account, "id")
            val info = CollectionInfo(CollectionInfo.Type.ADDRESS_BOOK, bookId, account.name, context)
            val extra = Extra(info, context, provider)
            val decsync = getDecsync(info)
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
                    values.put(LocalContact.COLUMN_LOCAL_BOOKID, bookId)
                    LocalContact(addressBook, values).writeUpdateAction(decsync)
                    if (newContact) {
                        addToNumProcessedEntries(extra, 1)
                    }
                }
            }

            decsync.executeAllNewEntries(extra)
        }
    }
}

fun syncAdapterUri(account: Account, uri: Uri): Uri {
    return uri.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()
}
