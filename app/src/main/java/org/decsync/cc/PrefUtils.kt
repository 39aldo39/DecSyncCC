/**
 * DecSyncCC - PrefUtils.kt
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

import android.content.Context
import android.os.Environment
import android.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.decsync.cc.calendars.CalendarsWorker
import org.decsync.cc.contacts.ContactsWorker
import org.decsync.library.getAppId
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

object PrefUtils {
    const val APP_VERSION = "app.version"
    const val DECSYNC_DIRECTORY = "decsync.directory"
    const val DECSYNC_DIRECTORY_RESET = "decsync.directory_reset"
    const val OWN_APP_ID = "own_app_id"
    const val HINT_BATTERY_OPTIMIZATIONS = "hint.battery_optimizations"
    const val OFFLINE_SYNC = "offline_sync"
    const val OFFLINE_SYNC_CALENDARS = "offline_sync.calendars"
    const val OFFLINE_SYNC_CONTACTS = "offline_sync.contacts"
    const val CALENDAR_ACCOUNT_NAME = "calendar_account_name"

    val currentAppVersion = 2
    val defaultDecsyncDir = File("${Environment.getExternalStorageDirectory()}/DecSync")

    fun getAppVersion(context: Context): Int {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getInt(APP_VERSION, 0)
    }

    fun putAppVersion(context: Context, value: Int) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putInt(APP_VERSION, value)
        editor.apply()
    }

    fun getDecsyncDir(context: Context): File {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return File(settings.getString(DECSYNC_DIRECTORY, defaultDecsyncDir.path)!!)
    }

    fun putDecsyncDir(context: Context, value: File) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putString(DECSYNC_DIRECTORY, value.path)
        editor.apply()
    }

    fun getOwnAppId(context: Context): String {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getString(OWN_APP_ID, null) ?: run {
            val id = Random().nextInt(100000)
            getAppId("DecSyncCC", id).also { appId ->
                putOwnAppId(context, appId)
            }
        }
    }

    fun putOwnAppId(context: Context, value: String) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putString(OWN_APP_ID, value)
        editor.apply()
    }

    fun getHintBatteryOptimizations(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getBoolean(HINT_BATTERY_OPTIMIZATIONS, true)
    }

    fun putHintBatteryOptimizations(context: Context, value: Boolean) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putBoolean(HINT_BATTERY_OPTIMIZATIONS, value)
        editor.apply()
    }

    fun getOfflineSync(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getBoolean(OFFLINE_SYNC, false)
    }

    fun updateOfflineSync(context: Context, doOfflineSync: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (doOfflineSync) {
            val calendarsWorkRequest = PeriodicWorkRequest.Builder(CalendarsWorker::class.java, 1, TimeUnit.HOURS)
                    .addTag(OFFLINE_SYNC)
                    .build()
            val contactsWorkRequest = PeriodicWorkRequest.Builder(ContactsWorker::class.java, 1, TimeUnit.HOURS)
                    .addTag(OFFLINE_SYNC)
                    .build()
            workManager.enqueueUniquePeriodicWork(OFFLINE_SYNC_CALENDARS, ExistingPeriodicWorkPolicy.REPLACE, calendarsWorkRequest)
            workManager.enqueueUniquePeriodicWork(OFFLINE_SYNC_CONTACTS, ExistingPeriodicWorkPolicy.REPLACE, contactsWorkRequest)
        } else {
            workManager.cancelAllWorkByTag(OFFLINE_SYNC)
        }
    }

    fun getCalendarAccountName(context: Context): String {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getString(CALENDAR_ACCOUNT_NAME, null) ?: run {
            context.getString(R.string.account_name_calendars).also { name ->
                putCalendarAccountName(context, name)
            }
        }
    }

    fun putCalendarAccountName(context: Context, value: String) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putString(CALENDAR_ACCOUNT_NAME, value)
        editor.apply()
    }

    fun checkAppUpgrade(context: Context) {
        val appVersion = getAppVersion(context)
        if (appVersion != currentAppVersion) {
            if (appVersion > 0) {
                if (appVersion < 2) {
                    putOwnAppId(context, getAppId("DecSyncCC"))
                    putCalendarAccountName(context, "DecSync Calendars")
                }
            }
            putAppVersion(context, currentAppVersion)
        }
    }
}
