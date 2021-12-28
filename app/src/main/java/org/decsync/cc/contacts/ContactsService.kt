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

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Service
import android.content.*
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.runBlocking
import org.decsync.cc.*

@ExperimentalStdlibApi
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
        override fun onPerformSync(account: Account, extras: Bundle,
                                   authority: String, provider: ContentProviderClient,
                                   syncResult: SyncResult) {
            // Only queue as the SyncAdapter is canceled when it doesn't use the internet in the first minute
            // Mainly used to get notified about changes in the data
            runBlocking {
                PrefUtils.checkAppUpgrade(context)
                val accountManager = AccountManager.get(context)
                val dirId = accountManager.getUserData(account, AddressBookInfo.KEY_DECSYNC_DIR_ID).toLong()
                val decsyncDir = App.db.decsyncDirectoryDao().find(dirId)
                if (decsyncDir != null) {
                    val id = accountManager.getUserData(account, AddressBookInfo.KEY_COLLECTION_ID)
                    val name = accountManager.getUserData(account, AddressBookInfo.KEY_NAME)
                    val info = AddressBookInfo(decsyncDir, id, name, false)
                    CollectionWorker.enqueue(context, info)
                }
            }
        }
    }
}