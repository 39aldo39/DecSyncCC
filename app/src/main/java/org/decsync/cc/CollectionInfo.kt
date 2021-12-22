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
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import at.bitfire.ical4android.AndroidCalendar
import at.bitfire.ical4android.TaskProvider
import org.decsync.cc.calendars.CalendarsUtils
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.cc.tasks.LocalTaskList

// TODO: store collection info in database for faster loading
sealed class CollectionInfo (
    val decsyncDir: DecsyncDirectory,
    val syncType: String,
    val id: String,
    val name: String,
    val color: Int?,
    val deleted: Boolean
) {
    val notificationId = 31 * 31 * decsyncDir.id.hashCode() + 31 * id.hashCode() + syncType.hashCode()

    abstract fun getAccount(context: Context): Account
    abstract fun getProviderClient(context: Context): ContentProviderClient?
    abstract fun isEnabled(context: Context): Boolean
    abstract fun create(context: Context)
    abstract fun remove(context: Context)
    abstract fun getPermissions(context: Context): List<String>
}

class AddressBookInfo(decsyncDir: DecsyncDirectory, id: String, name: String, deleted: Boolean) :
        CollectionInfo(decsyncDir, "contacts", id, name, null, deleted) {
    override fun getAccount(context: Context): Account {
        return decsyncDir.getContactsAccount(context, name)
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

    override fun create(context: Context) {
        val accountManager = AccountManager.get(context)
        val account = getAccount(context)
        val bundle = Bundle()
        bundle.putString(KEY_DECSYNC_DIR_ID, decsyncDir.id.toString())
        bundle.putString(KEY_COLLECTION_ID, id)
        bundle.putString(KEY_NAME, name)
        accountManager.addAccountExplicitly(account, null, bundle)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        ContentResolver.addPeriodicSync(account, ContactsContract.AUTHORITY, Bundle(), 60 * 15)
    }

    override fun remove(context: Context) {
        val account = getAccount(context)
        if (Build.VERSION.SDK_INT >= 22) {
            AccountManager.get(context).removeAccountExplicitly(account)
        } else {
            @Suppress("deprecation")
            AccountManager.get(context).removeAccount(account, null, null)
        }
    }

    override fun getPermissions(context: Context): List<String> {
        return listOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
        )
    }

    companion object {
        const val KEY_DECSYNC_DIR_ID = "decsync_dir_id"
        const val KEY_COLLECTION_ID = "id"
        const val KEY_NAME = "name"
    }
}

@ExperimentalStdlibApi
class CalendarInfo(decsyncDir: DecsyncDirectory, id: String, name: String, color: Int?, deleted: Boolean) :
        CollectionInfo(decsyncDir, "calendars", id, name, color, deleted) {
    override fun getAccount(context: Context): Account {
        return decsyncDir.getCalendarAccount(context)
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
            getProviderClient(context)?.let { provider ->
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

    override fun create(context: Context) {
        val account = getAccount(context)
        val success = AccountManager.get(context).addAccountExplicitly(account, null, null)
        if (success) {
            ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
            ContentResolver.addPeriodicSync(account, CalendarContract.AUTHORITY, Bundle(), 60 * 15)
        }

        val values = ContentValues()
        values.put(Calendars.ACCOUNT_NAME, account.name)
        values.put(Calendars.ACCOUNT_TYPE, account.type)
        values.put(Calendars.OWNER_ACCOUNT, account.name)
        values.put(Calendars.VISIBLE, 1)
        values.put(Calendars.SYNC_EVENTS, 1)
        values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
        values.put(Calendars.NAME, id)
        values.put(Calendars.CALENDAR_DISPLAY_NAME, name)
        if (color != null) {
            CalendarsUtils.addColor(values, color)
        }
        getProviderClient(context)?.let { provider ->
            try {
                provider.insert(syncAdapterUri(account, Calendars.CONTENT_URI), values)
                AndroidCalendar.insertColors(provider, account) // Allow custom event colors
            } finally {
                if (Build.VERSION.SDK_INT >= 24)
                    provider.close()
                else
                    @Suppress("DEPRECATION")
                    provider.release()
            }
        }
    }

    override fun remove(context: Context) {
        val account = getAccount(context)
        getProviderClient(context)?.let { provider ->
            try {
                provider.delete(syncAdapterUri(account, Calendars.CONTENT_URI),
                        "${Calendars.NAME}=?", arrayOf(id))
            } finally {
                if (Build.VERSION.SDK_INT >= 24)
                    provider.close()
                else
                    @Suppress("DEPRECATION")
                    provider.release()
            }
        }
    }

    override fun getPermissions(context: Context): List<String> {
        return listOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
        )
    }
}

@ExperimentalStdlibApi
class TaskListInfo(decsyncDir: DecsyncDirectory, id: String, name: String, color: Int?, deleted: Boolean) :
        CollectionInfo(decsyncDir, "tasks", id, name, color, deleted) {
    override fun getAccount(context: Context): Account {
        return decsyncDir.getTaskListAccount(context)
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

    override fun create(context: Context) {
        getProvider(context)?.use { provider ->
            val account = getAccount(context)
            val success = AccountManager.get(context).addAccountExplicitly(account, null, null)
            if (success) {
                val authority = provider.name.authority
                ContentResolver.setSyncAutomatically(account, authority, true)
                ContentResolver.addPeriodicSync(account, authority, Bundle(), 60 * 15)
            }
            LocalTaskList.create(account, provider, this)
        }
    }

    override fun remove(context: Context) {
        val account = getAccount(context)
        getProvider(context)?.use { provider ->
            LocalTaskList.findBySyncId(account, provider, id)?.delete()
        }
    }

    fun getProviderName(context: Context): TaskProvider.ProviderName? {
        val authority = PrefUtils.getTasksAuthority(context) ?: return null
        return TaskProvider.ProviderName.fromAuthority(authority)
    }

    fun getProvider(context: Context): TaskProvider? {
        val providerName = getProviderName(context) ?: return null
        return TaskProvider.acquire(context, providerName)
    }

    fun getTaskList(context: Context): LocalTaskList? {
        val account = getAccount(context)
        return getProvider(context)?.use { provider ->
            LocalTaskList.findBySyncId(account, provider, id)
        }
    }

    override fun getPermissions(context: Context): List<String> {
        val providerName = getProviderName(context) ?: return emptyList()
        return providerName.permissions.toList()
    }
}