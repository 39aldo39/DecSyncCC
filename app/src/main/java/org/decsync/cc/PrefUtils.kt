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
import java.io.File

object PrefUtils {
    const val APP_VERSION = "app.version"
    const val DECSYNC_DIRECTORY = "decsync.directory"
    const val DECSYNC_DIRECTORY_RESET = "decsync.directory_reset"
    const val HINT_BATTERY_OPTIMIZATIONS = "hint.battery_optimizations"

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

    fun getHintBatteryOptimizations(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getBoolean(HINT_BATTERY_OPTIMIZATIONS, true)
    }

    fun putHintBatteryOptimizations(context: Context, value: Boolean) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putBoolean(HINT_BATTERY_OPTIMIZATIONS, value)
        editor.apply()
    }
}
