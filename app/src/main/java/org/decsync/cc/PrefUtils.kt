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
import android.os.Build
import android.os.Environment
import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.decsync.cc.calendars.CalendarsWorker
import org.decsync.cc.contacts.ContactsWorker
import org.decsync.library.*
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

object PrefUtils {
    const val DECSYNC_USE_SAF = "decsync.use_saf"
    const val DECSYNC_FILE = "decsync.directory"
    const val DECSYNC_FILE_RESET = "decsync.directory_reset"
    const val APP_VERSION = "app.version"
    const val INTRO_DONE = "intro.done"
    const val UPDATE_FORCES_SAF = "update_forces_saf"
    const val OWN_APP_ID = "own_app_id"
    const val HINT_BATTERY_OPTIMIZATIONS = "hint.battery_optimizations"
    const val THEME = "theme"
    const val OFFLINE_SYNC = "offline_sync"
    const val OFFLINE_SYNC_CALENDARS = "offline_sync.calendars"
    const val OFFLINE_SYNC_CONTACTS = "offline_sync.contacts"
    const val CALENDAR_ACCOUNT_NAME = "calendar_account_name"

    val currentAppVersion = 3
    val defaultDecsyncDir = "${Environment.getExternalStorageDirectory()}/DecSync"

    fun getAppVersion(context: Context): Int {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getInt(APP_VERSION, 0)
    }

    fun putAppVersion(context: Context, value: Int) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putInt(APP_VERSION, value)
        editor.apply()
    }

    @ExperimentalStdlibApi
    fun getNativeFile(context: Context): NativeFile? {
        return if (getUseSaf(context)) {
            val decsyncDir = DecsyncPrefUtils.getDecsyncDir(context) ?: return null
            nativeFileFromDirUri(context, decsyncDir)
        } else {
            nativeFileFromFile(getDecsyncFile(context))
        }
    }

    fun getUseSaf(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getBoolean(DECSYNC_USE_SAF, false)
    }

    fun putUseSaf(context: Context, value: Boolean) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putBoolean(DECSYNC_USE_SAF, value)
        editor.apply()
    }

    fun getDecsyncFile(context: Context): File {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return File(settings.getString(DECSYNC_FILE, defaultDecsyncDir)!!)
    }

    fun putDecsyncFile(context: Context, value: File) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putString(DECSYNC_FILE, value.path)
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

    fun getIntroDone(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getBoolean(INTRO_DONE, false)
    }

    fun putIntroDone(context: Context, value: Boolean) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putBoolean(INTRO_DONE, value)
        editor.apply()
    }

    fun getUpdateForcesSaf(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getBoolean(UPDATE_FORCES_SAF, false)
    }

    fun putUpdateForcesSaf(context: Context, value: Boolean) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putBoolean(UPDATE_FORCES_SAF, value)
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

    fun notifyTheme(context: Context) {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        val mode = Integer.parseInt(settings.getString(THEME, null) ?: "-1")
        AppCompatDelegate.setDefaultNightMode(mode)
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
                if (appVersion < 3) {
                    putIntroDone(context, true)
                }
            }
            putAppVersion(context, currentAppVersion)
        }

        if (Build.VERSION.SDK_INT >= 29 &&
                !getUseSaf(context) &&
                !Environment.isExternalStorageLegacy()) {
            putUseSaf(context, true)
            if (getIntroDone(context)) {
                putUpdateForcesSaf(context, true)
            }
        }
    }
}
