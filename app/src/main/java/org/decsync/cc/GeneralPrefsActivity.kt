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
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.preference.PreferenceFragmentCompat
import android.view.MenuItem
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.Utils
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.DecsyncException
import org.decsync.library.checkDecsyncInfo

const val CHOOSE_DECSYNC_DIRECTORY = 0

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

    class GeneralPrefsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.general_preferences, rootKey)
        }

        override fun onResume() {
            super.onResume()

            setDecsyncDirSummary()

            val preference = findPreference(PrefUtils.DECSYNC_DIRECTORY)
            preference.setOnPreferenceClickListener {
                val context = activity!!

                val addressBookAccounts = AccountManager.get(context).getAccountsByType(getString(R.string.account_type_contacts))
                val anyAddressBookEnabled = addressBookAccounts.isNotEmpty()

                var anyCalendarEnabled = false
                val calendarsAccount = Account(getString(R.string.account_name_calendars), getString(R.string.account_type_calendars))
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                    context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
                        anyCalendarEnabled = try {
                            provider.query(syncAdapterUri(calendarsAccount, CalendarContract.Calendars.CONTENT_URI), emptyArray(),
                                    null, null, null).use { cursor ->
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

                if (anyAddressBookEnabled || anyCalendarEnabled) {
                    AlertDialog.Builder(context)
                            .setTitle("Enabled collections")
                            .setMessage("There are still some collections enabled. Disable all collections before changing the DecSync directory.")
                            .setNeutralButton("OK") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .show()
                } else {
                    val intent = Intent(context, FilePickerActivity::class.java)
                    intent.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true)
                    intent.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_DIR)
                    intent.putExtra(FilePickerActivity.EXTRA_START_PATH, PrefUtils.getDecsyncDir(context))
                    startActivityForResult(intent, CHOOSE_DECSYNC_DIRECTORY)
                }
                true
            }
        }

        private fun setDecsyncDirSummary() {
            val preference = findPreference(PrefUtils.DECSYNC_DIRECTORY)
            val dir = PrefUtils.getDecsyncDir(activity!!)
            preference.summary = dir
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)

            if (requestCode == CHOOSE_DECSYNC_DIRECTORY) {
                val context = activity!!
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    val oldDir = PrefUtils.getDecsyncDir(context)
                    val newDir = Utils.getFileForUri(uri).path
                    if (oldDir != newDir) {
                        try {
                            checkDecsyncInfo(PrefUtils.getDecsyncDir(context))
                            PrefUtils.putDecsyncDir(context, newDir)
                            setDecsyncDirSummary()
                        } catch (e: DecsyncException) {
                            AlertDialog.Builder(context)
                                    .setTitle("DecSync")
                                    .setMessage(e.message)
                                    .setPositiveButton("OK") { _, _ -> }
                                    .show()
                        }
                    }
                }
            }
        }
    }
}
