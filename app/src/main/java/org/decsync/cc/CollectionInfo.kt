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
import at.bitfire.ical4android.TaskProvider
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.cc.tasks.LocalTaskList

sealed class CollectionInfo (
        val syncType: String,
        val id: String,
        val name: String,
        val color: Int?
) {
    val notificationId = 31 * id.hashCode() + syncType.hashCode()

    abstract fun getAccount(context: Context): Account
    abstract fun getProviderClient(context: Context): ContentProviderClient?
    abstract fun isEnabled(context: Context): Boolean
}

class AddressBookInfo(id: String, name: String) :
        CollectionInfo("contacts", id, name, null) {
    override fun getAccount(context: Context): Account {
        val accountName = name
        val accountType = context.getString(R.string.account_type_contacts)
        return Account(accountName, accountType)
    }

    override fun getProviderClient(context: Context): ContentProviderClient? {
        return try {
            context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
        } catch (e: SecurityException) {
            null
        }
    }

    override fun isEnabled(context: Context): Boolean {
        val account = getAccount(context)
        return AccountManager.get(context).getAccountsByType(account.type).any { it.name == account.name }
    }
}

class CalendarInfo(id: String, name: String, color: Int?) :
        CollectionInfo("calendars", id, name, color) {
    override fun getAccount(context: Context): Account {
        val accountName = PrefUtils.getCalendarAccountName(context)
        val accountType = context.getString(R.string.account_type_calendars)
        return Account(accountName, accountType)
    }

    override fun getProviderClient(context: Context): ContentProviderClient? {
        return try {
            context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
        } catch (e: SecurityException) {
            null
        }
    }

    override fun isEnabled(context: Context): Boolean {
        val account = getAccount(context)
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
        return result
    }
}

@ExperimentalStdlibApi
class TaskListInfo(id: String, name: String, color: Int?) :
        CollectionInfo("tasks", id, name, color) {
    override fun getAccount(context: Context): Account {
        val accountName = PrefUtils.getTasksAccountName(context)
        val accountType = context.getString(R.string.account_type_tasks)
        return Account(accountName, accountType)
    }

    override fun getProviderClient(context: Context): ContentProviderClient? {
        return getProvider(context)?.client
    }

    override fun isEnabled(context: Context): Boolean {
        return try {
            getTaskList(context) != null
        } catch (e: SecurityException) {
            false
        }
    }

    fun getProvider(context: Context): TaskProvider? {
        val authority = PrefUtils.getTasksAuthority(context) ?: return null
        val providerName = TaskProvider.ProviderName.fromAuthority(authority)
        return TaskProvider.acquire(context, providerName)
    }

    fun getTaskList(context: Context): LocalTaskList? {
        val account = getAccount(context)
        return getProvider(context)?.use { provider ->
            LocalTaskList.findBySyncId(account, provider, id)
        }
    }
}