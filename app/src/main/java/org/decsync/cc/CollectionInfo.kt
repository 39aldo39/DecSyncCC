/**
 * DecSyncCC - CollectionInfo.kt
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

package org.decsync.cc

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.decsync.cc.contacts.syncAdapterUri

class CollectionInfo (
        val type: Type,
        val id: String,
        val name: String,
        context: Context
) {
    val decsyncDir = PrefUtils.getDecsyncDir(context)
    val syncType = type.toString()
    val collection = id
    val appId = PrefUtils.getOwnAppId(context)
    val notificationId = 2 * id.hashCode() + type.ordinal

    enum class Type {
        ADDRESS_BOOK,
        CALENDAR;

        override fun toString(): String =
            when (this) {
                ADDRESS_BOOK -> "contacts"
                CALENDAR -> "calendars"
            }
    }

    fun getAccount(context: Context): Account {
        val accountName = when (type) {
            Type.ADDRESS_BOOK -> name
            Type.CALENDAR -> PrefUtils.getCalendarAccountName(context)
        }
        val accountType = when (type) {
            Type.ADDRESS_BOOK -> context.getString(R.string.account_type_contacts)
            Type.CALENDAR -> context.getString(R.string.account_type_calendars)
        }
        return Account(accountName, accountType)
    }

    fun getProviderClient(context: Context): ContentProviderClient? =
        context.contentResolver.acquireContentProviderClient(
                when (type) {
                    Type.ADDRESS_BOOK -> ContactsContract.AUTHORITY
                    Type.CALENDAR -> CalendarContract.AUTHORITY
                }
        )

    fun isEnabled(context: Context): Boolean {
        val account = getAccount(context)
        return when (type) {
            Type.ADDRESS_BOOK -> {
                AccountManager.get(context).getAccountsByType(account.type).any { it.name == account.name }
            }
            Type.CALENDAR -> {
                var result = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                    context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
                        try {
                            provider.query(syncAdapterUri(account, Calendars.CONTENT_URI), emptyArray(),
                                    "${Calendars.NAME}=?", arrayOf(id), null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    result = true
                                }
                            }
                        } finally {
                            if (Build.VERSION.SDK_INT >= 24)
                                provider.close()
                            else
                                @Suppress("DEPRECATION")
                                provider.release()
                        }
                    }
                }
                result
            }
        }
    }
}
