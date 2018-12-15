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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.preference.PreferenceFragmentCompat
import android.view.MenuItem
import org.decsync.library.Decsync
import org.decsync.library.getDecsyncSubdir
import org.decsync.library.listDecsyncCollections
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.Utils

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
                val decsyncBaseDir = PrefUtils.getDecsyncDir(activity!!)

                val anyAddressBookEnabled = listDecsyncCollections(decsyncBaseDir, "contacts").any { collection ->
                    val dir = getDecsyncSubdir(decsyncBaseDir, "contacts", collection)
                    val name = Decsync.getStoredStaticValue(dir, listOf("info"), "name") as? String ?: collection
                    val info = CollectionInfo(CollectionInfo.Type.ADDRESS_BOOK, collection, name, activity!!)
                    info.isEnabled(activity!!)
                }

                val anyCalendarEnabled = listDecsyncCollections(decsyncBaseDir, "calendars").any { collection ->
                    val dir = getDecsyncSubdir(decsyncBaseDir, "calendars", collection)
                    val name = Decsync.getStoredStaticValue(dir, listOf("info"), "name") as? String ?: collection
                    val info = CollectionInfo(CollectionInfo.Type.CALENDAR, collection, name, activity!!)
                    info.isEnabled(activity!!)
                }

                if (anyAddressBookEnabled || anyCalendarEnabled) {
                    AlertDialog.Builder(activity!!)
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
                    intent.putExtra(FilePickerActivity.EXTRA_START_PATH, PrefUtils.getDecsyncDir(activity!!))
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
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    val oldDir = PrefUtils.getDecsyncDir(activity!!)
                    val newDir = Utils.getFileForUri(uri).path
                    if (oldDir != newDir) {
                        PrefUtils.putDecsyncDir(activity!!, newDir)
                        setDecsyncDirSummary()
                    }
                }
            }
        }
    }
}
