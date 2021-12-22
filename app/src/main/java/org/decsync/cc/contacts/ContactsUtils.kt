package org.decsync.cc.contacts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import org.decsync.cc.AddressBookInfo
import org.decsync.cc.CollectionInfo
import org.decsync.cc.Extra
import org.decsync.cc.KEY_NUM_PROCESSED_ENTRIES

@ExperimentalStdlibApi
object ContactsUtils {
    fun moveContacts(oldAccount: Account, newInfo: AddressBookInfo, extra: Extra) {
        // NOTE: It is possible to temporarily have 2 accounts with the same name, since the names may be swapped
        val newAccount = newInfo.getAccount(extra.context)
        val accountManager = AccountManager.get(extra.context)

        // Create the new account if needed
        accountManager.addAccountExplicitly(newAccount, null, null)
        accountManager.setUserData(newAccount, AddressBookInfo.KEY_DECSYNC_DIR_ID, newInfo.decsyncDir.id.toString()) // Separate, since the account may exist
        accountManager.setUserData(newAccount, AddressBookInfo.KEY_COLLECTION_ID, newInfo.id)
        accountManager.setUserData(newAccount, AddressBookInfo.KEY_NAME, newInfo.name)
        accountManager.setUserData(newAccount, KEY_NUM_PROCESSED_ENTRIES, null)
        ContentResolver.setSyncAutomatically(newAccount, ContactsContract.AUTHORITY, true)
        ContentResolver.addPeriodicSync(newAccount, ContactsContract.AUTHORITY, Bundle(), 60 * 60)

        // Move the contacts to the new account
        val values = ContentValues()
        values.put(ContactsContract.RawContacts.ACCOUNT_NAME, newAccount.name)
        extra.provider.update(syncAdapterUri(oldAccount, ContactsContract.RawContacts.CONTENT_URI), values,
            "${LocalContact.COLUMN_LOCAL_BOOKID}=?", arrayOf(newInfo.id))

        // Delete the old account if it has no contacts
        extra.provider.query(syncAdapterUri(oldAccount, ContactsContract.RawContacts.CONTENT_URI), emptyArray(),
            null, null, null)!!.use { cursor ->
            if (!cursor.moveToFirst()) {
                if (Build.VERSION.SDK_INT >= 22) {
                    AccountManager.get(extra.context).removeAccountExplicitly(oldAccount)
                } else {
                    @Suppress("deprecation")
                    AccountManager.get(extra.context).removeAccount(oldAccount, null, null)
                }
            }
        }
    }
}