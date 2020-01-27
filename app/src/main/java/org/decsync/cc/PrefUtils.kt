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
import androidx.preference.PreferenceManager

object PrefUtils {
    const val OLD_DECSYNC_DIR = "decsync.directory"
    const val INTRO_DONE = "intro.done"
    const val HINT_BATTERY_OPTIMIZATIONS = "hint.battery_optimizations"

    fun getIntroDone(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.getBoolean(INTRO_DONE, false)
    }

    fun putIntroDone(context: Context, value: Boolean) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editor.putBoolean(INTRO_DONE, value)
        editor.remove(OLD_DECSYNC_DIR)
        editor.apply()
    }

    fun hasOldDecsyncDir(context: Context): Boolean {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        return settings.contains(OLD_DECSYNC_DIR)
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
