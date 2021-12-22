/**
 * DecSyncCC - GeneralPrefsActivity.kt
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

package org.decsync.cc.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import at.bitfire.ical4android.TaskProvider
import org.decsync.cc.*
import org.decsync.cc.R

class GeneralPrefsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        PrefUtils.notifyTheme(this)
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(R.layout.activity_general_prefs)
    }

    override fun onOptionsItemSelected(item: MenuItem) =
            if (item.itemId == android.R.id.home) {
                finish()
                true
            } else {
                false
            }

    @ExperimentalStdlibApi
    class GeneralPrefsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.general_preferences, rootKey)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            initTaskApp()
            initTheme()
        }

        private fun initTaskApp() {
            val preference = findPreference<ConditionalListPreference>(PrefUtils.TASKS_AUTHORITY)!!
            preference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            preference.entries += Utils.TASK_PROVIDER_NAMES.map { getString(it) }.toTypedArray()
            preference.entryValues += Utils.TASK_PROVIDERS.map { it.authority }.toTypedArray()
            preference.setOnPreferenceChangeListener { _, newValue ->
                val authority = newValue as String
                if (authority.isEmpty()) return@setOnPreferenceChangeListener true
                val providerName = TaskProvider.ProviderName.fromAuthority(authority)
                val installed = Utils.appInstalled(requireActivity(), providerName.packageName)
                if (!installed) {
                    Utils.installApp(requireActivity(), providerName.packageName)
                }
                installed
            }
            preference.condition = {
                !Utils.checkTaskListsEnabled(requireActivity())
            }
        }

        private fun initTheme() {
            val preference = findPreference<Preference>(PrefUtils.THEME)!!
            preference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            preference.setOnPreferenceChangeListener { _, newValue ->
                val mode = Integer.parseInt(newValue as String)
                AppCompatDelegate.setDefaultNightMode(mode)
                true
            }
        }
    }
}
