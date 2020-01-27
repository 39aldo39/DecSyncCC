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

package org.decsync.cc

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.DecsyncPrefUtils
import org.decsync.library.checkDecsyncInfo

class GeneralPrefsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

        override fun onResume() {
            super.onResume()

            initDecsyncDir()
        }

        private fun initDecsyncDir() {
            val preference = findPreference<Preference>(DecsyncPrefUtils.DECSYNC_DIRECTORY)!!
            preference.setOnPreferenceClickListener {
                if (!checkDecsyncFine() || !checkCollectionEnabled()) {
                    DecsyncPrefUtils.chooseDecsyncDir(this)
                }
                true
            }
        }

        private fun checkDecsyncFine(): Boolean = try {
            DecsyncPrefUtils.getDecsyncDir(activity!!)?.let {
                checkDecsyncInfo(it, activity!!.contentResolver)
            } ?: throw Exception("No DecSync dir configured")
            true
        } catch (e: Exception) {
            false
        }

        private fun checkCollectionEnabled(): Boolean {
            val context = activity!!

            val addressBookAccounts = AccountManager.get(context).getAccountsByType(getString(R.string.account_type_contacts))
            val anyAddressBookEnabled = addressBookAccounts.isNotEmpty()

            var anyCalendarEnabled = false
            val calendarsAccount = Account(getString(R.string.account_name_calendars), getString(R.string.account_type_calendars))
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
                    anyCalendarEnabled = try {
                        provider.query(syncAdapterUri(calendarsAccount, CalendarContract.Calendars.CONTENT_URI), emptyArray(),
                                null, null, null)!!.use { cursor ->
                            cursor.moveToFirst()
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

            return if (anyAddressBookEnabled || anyCalendarEnabled) {
                AlertDialog.Builder(context)
                        .setTitle("Enabled collections")
                        .setMessage("There are still some collections enabled. Disable all collections before changing the DecSync directory.")
                        .setNeutralButton("OK") { _, _ -> }
                        .show()
                true
            } else {
                false
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)

            DecsyncPrefUtils.chooseDecsyncDirResult(activity!!, requestCode, resultCode, data)
        }
    }
}
