/*
 * Copyright © 2018 Aldo Gunsing.
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package org.decsync.cc

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.TargetApi
import android.content.*
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import at.bitfire.ical4android.AndroidCalendar
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonLiteral
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.content
import org.decsync.cc.calendars.COLUMN_NUM_PROCESSED_ENTRIES
import org.decsync.cc.calendars.CalendarDecsyncUtils
import org.decsync.cc.calendars.CalendarDecsyncUtils.addColor
import org.decsync.cc.calendars.CalendarsInitWorker
import org.decsync.cc.contacts.ContactDecsyncUtils
import org.decsync.cc.contacts.ContactsInitWorker
import org.decsync.cc.contacts.KEY_NUM_PROCESSED_ENTRIES
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.*
import java.util.Random
import kotlin.math.min

const val TAG = "DecSyncCC"

/**
 * Address book permission requests have a requestCode in the range [100, 199)
 * Calendar requests in the range [200, 299)
 * The exclusive ends are used for invalid items
 */
const val PERMISSIONS_ADDRESS_BOOK_START = 100
const val PERMISSIONS_ADDRESS_BOOK_END = 199
const val PERMISSIONS_CALENDAR_START = 200
const val PERMISSIONS_CALENDAR_END = 299

@ExperimentalStdlibApi
class MainActivity: AppCompatActivity(), Toolbar.OnMenuItemClickListener, PopupMenu.OnMenuItemClickListener {

    private var error = false

    override fun onCreate(savedInstanceState: Bundle?) {
        PrefUtils.notifyTheme(this)
        super.onCreate(savedInstanceState)

        PrefUtils.checkAppUpgrade(this)

        if (!PrefUtils.getIntroDone(this)) {
            val intent = Intent(this, IntroActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        if (PrefUtils.getUpdateForcesSaf(this)) {
            val intent = Intent(this, SafUpdateActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Address books toolbar
        contacts_menu.inflateMenu(R.menu.address_book_actions)
        contacts_menu.setOnMenuItemClickListener(this)

        // Calendars sync adapter
        val calendarsAccount = Account(PrefUtils.getCalendarAccountName(this), getString(R.string.account_type_calendars))
        val success = AccountManager.get(this).addAccountExplicitly(calendarsAccount, null, null)
        if (success) {
            ContentResolver.setSyncAutomatically(calendarsAccount, CalendarContract.AUTHORITY, true)
            ContentResolver.addPeriodicSync(calendarsAccount, CalendarContract.AUTHORITY, Bundle(), 60 * 60)
        }

        // Calendars toolbar
        calendars_menu.inflateMenu(R.menu.calendar_actions)
        calendars_menu.setOnMenuItemClickListener(this)

        // Ask for permissions
        if (!PrefUtils.getUseSaf(this) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
        }

        // Ask for exception to App Standby
        // TODO: also ask at intro
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && PrefUtils.getHintBatteryOptimizations(this)) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID)) {
                AlertDialog.Builder(this)
                        .setTitle(R.string.startup_battery_optimization)
                        .setMessage(R.string.startup_battery_optimization_message)
                        .setPositiveButton(R.string.startup_battery_optimization_disable) @TargetApi(Build.VERSION_CODES.M) { _, _ ->
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + BuildConfig.APPLICATION_ID))
                            startActivity(intent)
                        }
                        .setNeutralButton(R.string.startup_not_now) { _, _ -> }
                        .setNegativeButton(R.string.startup_dont_show_again) { _: DialogInterface, _: Int ->
                            PrefUtils.putHintBatteryOptimizations(this, false)
                        }
                        .show()
            }
        }

        // Adapters
        address_books.adapter = AddressBookAdapter(this)
        address_books.onItemClickListener = onItemClickListener

        address_books_unknown.adapter = AddressBookUnknownAdapter(this)
        address_books_unknown.onItemClickListener = onAddressBookUnknownClickListener

        calendars.adapter = CalendarAdapter(this)
        calendars.onItemClickListener = onItemClickListener

        calendars_unknown.adapter = CalendarUnknownAdapter(this)
        calendars_unknown.onItemClickListener = onCalendarUnknownClickListener
    }

    override fun onResume() {
        super.onResume()
        val decsyncDir = if (!PrefUtils.getUseSaf(this) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            null
        } else try {
            PrefUtils.getNativeFile(this)?.also {
                checkDecsyncInfo(it)
            } ?: throw Exception(getString(R.string.settings_decsync_dir_not_configured))
        } catch (e: Exception) {
            error = true
            AlertDialog.Builder(this)
                    .setTitle("DecSync")
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
            null
        }

        GlobalScope.launch {
            val decsyncAddressBookIds = decsyncDir?.let(::loadBooks) ?: emptyList()
            loadBooksUnknown(decsyncAddressBookIds)
            val decsyncCalendarIds = decsyncDir?.let(::loadCalendars) ?: emptyList()
            loadCalendarsUnknown(decsyncCalendarIds)
        }
    }

    private fun loadBooks(decsyncDir: NativeFile): List<String> {
        val decsyncIds = listDecsyncCollections(decsyncDir, "contacts")
        val collectionInfos = decsyncIds.mapNotNull { id ->
            val info = Decsync.getStaticInfo(decsyncDir, "contacts", id)
            val deleted = info[JsonLiteral("deleted")]?.boolean ?: false
            if (!deleted) {
                val name = info[JsonLiteral("name")]?.content ?: id
                CollectionInfo(CollectionInfo.Type.ADDRESS_BOOK, id, name, this)
            } else {
                null
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = address_books.adapter as AddressBookAdapter
            adapter.clear()
            adapter.addAll(collectionInfos)
        }

        return decsyncIds
    }

    private fun loadBooksUnknown(decsyncIds: List<String>) {
        val accountManager = AccountManager.get(this)
        val accounts = accountManager.getAccountsByType(getString(R.string.account_type_contacts)).filter { account ->
            val bookId = accountManager.getUserData(account, "id")
            bookId !in decsyncIds
        }

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = address_books_unknown.adapter as AddressBookUnknownAdapter
            adapter.clear()
            adapter.addAll(accounts)
            address_books_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun loadCalendars(decsyncDir: NativeFile): List<String> {
        val decsyncIds = listDecsyncCollections(decsyncDir, "calendars")
        val collectionInfos = decsyncIds.mapNotNull {
            val info = Decsync.getStaticInfo(decsyncDir, "calendars", it)
            val deleted = info[JsonLiteral("deleted")]?.boolean ?: false
            if (!deleted) {
                val name = info[JsonLiteral("name")]?.content ?: it
                CollectionInfo(CollectionInfo.Type.CALENDAR, it, name, this)
            } else {
                null
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = calendars.adapter as CalendarAdapter
            adapter.clear()
            adapter.addAll(collectionInfos)
        }

        return decsyncIds
    }

    private fun loadCalendarsUnknown(decsyncIds: List<String>) {
        val calendarsAccount = Account(PrefUtils.getCalendarAccountName(this), getString(R.string.account_type_calendars))
        val idAndNames = mutableListOf<DecsyncIdName>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
                try {
                    provider.query(syncAdapterUri(calendarsAccount, Calendars.CONTENT_URI),
                            arrayOf(Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME),
                            null, null, null)!!.use { cursor ->
                        while (cursor.moveToNext()) {
                            val decsyncId = cursor.getString(0)
                            val name = cursor.getString(1)
                            if (decsyncId !in decsyncIds) {
                                idAndNames.add(DecsyncIdName(decsyncId, name))
                            }
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

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = calendars_unknown.adapter as CalendarUnknownAdapter
            adapter.clear()
            adapter.addAll(idAndNames)
            calendars_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            in PERMISSIONS_ADDRESS_BOOK_START until PERMISSIONS_ADDRESS_BOOK_END -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    val position = requestCode - PERMISSIONS_ADDRESS_BOOK_START
                    val view = address_books.getChildAt(position)
                    val id = address_books.getItemIdAtPosition(position)
                    address_books.performItemClick(view, position, id)
                }
            }
            PERMISSIONS_ADDRESS_BOOK_END -> {} // Invalid position
            in PERMISSIONS_CALENDAR_START until PERMISSIONS_CALENDAR_END -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    val position = requestCode - PERMISSIONS_CALENDAR_START
                    val view = calendars.getChildAt(position)
                    val id = calendars.getItemIdAtPosition(position)
                    calendars.performItemClick(view, position, id)
                }
            }
            PERMISSIONS_CALENDAR_END -> {} // Invalid position
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sync_now -> {
                // Using sync adapter
                val extras = Bundle()
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) // Manual sync
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true) // Run immediately (don't queue)

                val calendarsAuthority = CalendarContract.AUTHORITY
                val calendarsAccount = Account(PrefUtils.getCalendarAccountName(this), getString(R.string.account_type_calendars))
                ContentResolver.requestSync(calendarsAccount, calendarsAuthority, extras)

                if (!error) {
                    val contactsAuthority = ContactsContract.AUTHORITY
                    val count = address_books.adapter.count
                    for (position in 0 until count) {
                        val info = address_books.adapter.getItem(position) as CollectionInfo
                        if (!info.isEnabled(this)) continue
                        val account = info.getAccount(this)
                        ContentResolver.requestSync(account, contactsAuthority, extras)
                    }
                }

                // Using work manager (if enabled)
                if (PrefUtils.getOfflineSync(this)) {
                    PrefUtils.updateOfflineSync(this, true)
                }

                Snackbar.make(findViewById(R.id.parent), R.string.account_synchronizing_now, Snackbar.LENGTH_LONG).show()
            }
            R.id.settings -> {
                val intent = Intent(this, GeneralPrefsActivity::class.java)
                startActivity(intent)
            }
            else ->
                return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (error) return false
        when (item.itemId) {
            R.id.create_address_book -> {
                if (!PrefUtils.getUseSaf(this) &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val input = EditText(this)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (!name.isBlank()) {
                                val id = "colID%05d".format(Random().nextInt(100000))
                                val info = CollectionInfo(CollectionInfo.Type.ADDRESS_BOOK, id, name, this)
                                val decsync = getDecsync(info)
                                decsync.setEntry(listOf("info"), JsonLiteral("name"), JsonLiteral(name))
                                loadBooks(info.decsyncDir)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.create_calendar -> {
                if (!PrefUtils.getUseSaf(this) &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val input = EditText(this)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (!name.isBlank()) {
                                val id = "colID%05d".format(Random().nextInt(100000))
                                val info = CollectionInfo(CollectionInfo.Type.CALENDAR, id, name, this)
                                val decsync = getDecsync(info)
                                decsync.setEntry(listOf("info"), JsonLiteral("name"), JsonLiteral(name))
                                loadCalendars(info.decsyncDir)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.change_calendar_colors -> {
                val intent = packageManager.getLaunchIntentForPackage("ch.ihdg.calendarcolor")
                        ?: Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://f-droid.org/app/ch.ihdg.calendarcolor")
                        }
                startActivity(intent)
            }
        }
        return false
    }

    private val onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, _ ->
        if (!view.isEnabled)
            return@OnItemClickListener

        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<CollectionInfo>
        val info = adapter.getItem(position)!!
        val nowChecked = !info.isEnabled(this)

        val account = info.getAccount(this)
        when (info.type) {
            CollectionInfo.Type.ADDRESS_BOOK -> {
                if (nowChecked) {
                    var permissions = emptyArray<String>()
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        permissions += Manifest.permission.READ_CONTACTS
                    }
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        permissions += Manifest.permission.WRITE_CONTACTS
                    }
                    if (permissions.isNotEmpty()) {
                        val requestCode = min(PERMISSIONS_ADDRESS_BOOK_START + position, PERMISSIONS_ADDRESS_BOOK_END)
                        ActivityCompat.requestPermissions(this, permissions, requestCode)
                        return@OnItemClickListener
                    }
                    val bundle = Bundle()
                    bundle.putString("id", info.id)
                    AccountManager.get(this).addAccountExplicitly(account, null, bundle)
                    ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
                    ContentResolver.addPeriodicSync(account, ContactsContract.AUTHORITY, Bundle(), 60 * 60)
                    val inputData = Data.Builder()
                            .putString(InitWorker.KEY_ID, info.id)
                            .putString(InitWorker.KEY_NAME, info.name)
                            .build()
                    val workRequest = OneTimeWorkRequest.Builder(ContactsInitWorker::class.java)
                            .setInputData(inputData)
                            .build()
                    WorkManager.getInstance(this).enqueueUniqueWork("${info.type}-${info.id}", ExistingWorkPolicy.REPLACE, workRequest)
                } else {
                    WorkManager.getInstance(this).cancelUniqueWork("${info.type}-${info.id}")
                    if (Build.VERSION.SDK_INT >= 22) {
                        AccountManager.get(this).removeAccountExplicitly(account)
                    } else {
                        @Suppress("deprecation")
                        AccountManager.get(this).removeAccount(account, null, null)
                    }
                }
            }
            CollectionInfo.Type.CALENDAR -> {
                var permissions = emptyArray<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.READ_CALENDAR
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.WRITE_CALENDAR
                }
                if (permissions.isNotEmpty()) {
                    val requestCode = min(PERMISSIONS_CALENDAR_START + position, PERMISSIONS_CALENDAR_END)
                    ActivityCompat.requestPermissions(this, permissions, requestCode)
                    return@OnItemClickListener
                }
                contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
                    try {
                        if (nowChecked) {
                            val values = ContentValues()
                            values.put(Calendars.ACCOUNT_NAME, account.name)
                            values.put(Calendars.ACCOUNT_TYPE, account.type)
                            values.put(Calendars.OWNER_ACCOUNT, account.name)
                            values.put(Calendars.VISIBLE, 1)
                            values.put(Calendars.SYNC_EVENTS, 1)
                            values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                            values.put(Calendars.NAME, info.id)
                            values.put(Calendars.CALENDAR_DISPLAY_NAME, info.name)
                            val decsyncInfo = Decsync.getStaticInfo(info.decsyncDir, info.syncType, info.collection)
                            val color = decsyncInfo[JsonLiteral("color")]?.content
                            if (color != null) {
                                addColor(values, color)
                            }
                            provider.insert(syncAdapterUri(account, Calendars.CONTENT_URI), values)
                            AndroidCalendar.insertColors(provider, account) // Allow custom event colors

                            val inputData = Data.Builder()
                                    .putString(InitWorker.KEY_ID, info.id)
                                    .putString(InitWorker.KEY_NAME, info.name)
                                    .build()
                            val workRequest = OneTimeWorkRequest.Builder(CalendarsInitWorker::class.java)
                                    .setInputData(inputData)
                                    .build()
                            WorkManager.getInstance(this).enqueueUniqueWork("${info.type}-${info.id}", ExistingWorkPolicy.REPLACE, workRequest)
                        } else {
                            WorkManager.getInstance(this).cancelUniqueWork("${info.type}-${info.id}")
                            provider.delete(syncAdapterUri(account, Calendars.CONTENT_URI),
                                    "${Calendars.NAME}=?", arrayOf(info.id))
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
        }
        adapter.notifyDataSetChanged()
    }

    private val onAddressBookUnknownClickListener = AdapterView.OnItemClickListener { parent, view, position, _ ->
        if (!view.isEnabled)
            return@OnItemClickListener

        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<Account>
        val account = adapter.getItem(position)!!

        if (Build.VERSION.SDK_INT >= 22) {
            AccountManager.get(this).removeAccountExplicitly(account)
        } else {
            @Suppress("deprecation")
            AccountManager.get(this).removeAccount(account, null, null)
        }
        adapter.remove(account)
    }

    private val onCalendarUnknownClickListener = AdapterView.OnItemClickListener { parent, view, position, _ ->
        if (!view.isEnabled)
            return@OnItemClickListener

        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<DecsyncIdName>
        val item = adapter.getItem(position)!!

        val calendarsAccount = Account(PrefUtils.getCalendarAccountName(this), getString(R.string.account_type_calendars))
        contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
            try {
                provider.delete(syncAdapterUri(calendarsAccount, Calendars.CONTENT_URI),
                        "${Calendars.NAME}=?", arrayOf(item.decsyncId))
            } finally {
                if (Build.VERSION.SDK_INT >= 24)
                    provider.close()
                else
                    @Suppress("DEPRECATION")
                    provider.release()
            }
        }

        adapter.remove(item)
    }

    private val onActionOverflowListener = { anchor: View, info: CollectionInfo ->
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.inflate(R.menu.account_collection_operations)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.rename_collection -> {
                    val input = EditText(this)
                    input.setText(info.name)
                    AlertDialog.Builder(this)
                            .setTitle(R.string.rename_collection_title)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val name = input.text.toString()
                                if (!name.isBlank() && name != info.name) {
                                    setCollectionInfo(info, JsonLiteral("name"), JsonLiteral(name))
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> }
                            .show()
                }
                R.id.delete_collection -> {
                    AlertDialog.Builder(this)
                            .setTitle(getString(R.string.delete_collection_title, info.name))
                            .setPositiveButton(android.R.string.yes) { _, _ ->
                                setCollectionInfo(info, JsonLiteral("deleted"), JsonLiteral(true))
                            }
                            .setNegativeButton(android.R.string.no) { _, _ -> }
                            .show()
                }
                R.id.entries_count -> {
                    var androidEntries = 0
                    var processedEntries = 0
                    info.getProviderClient(this)?.let { provider ->
                        try {
                            when (info.type) {
                                CollectionInfo.Type.ADDRESS_BOOK -> {
                                    processedEntries = AccountManager.get(this).getUserData(info.getAccount(this), KEY_NUM_PROCESSED_ENTRIES)?.toInt()
                                            ?: 0
                                    provider.query(syncAdapterUri(info.getAccount(this), RawContacts.CONTENT_URI),
                                            emptyArray(), "${RawContacts.DELETED}=0",
                                            null, null)!!.use { cursor ->
                                        androidEntries = cursor.count
                                    }
                                }
                                CollectionInfo.Type.CALENDAR -> {
                                    val calendarId = provider.query(syncAdapterUri(info.getAccount(this), Calendars.CONTENT_URI),
                                            arrayOf(Calendars._ID, COLUMN_NUM_PROCESSED_ENTRIES), "${Calendars.NAME}=?",
                                            arrayOf(info.id), null)!!.use { calCursor ->
                                        if (calCursor.moveToFirst()) {
                                            processedEntries = if (calCursor.isNull(1)) 0 else calCursor.getInt(1)
                                            calCursor.getLong(0)
                                        } else {
                                            null
                                        }
                                    }
                                    provider.query(syncAdapterUri(info.getAccount(this), Events.CONTENT_URI),
                                            emptyArray(), "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID} IS NULL AND ${Events.DELETED}=0",
                                            arrayOf(calendarId.toString()), null)!!.use { cursor ->
                                        androidEntries = cursor.count
                                    }
                                }
                            }
                        } finally {
                            if (Build.VERSION.SDK_INT >= 24)
                                provider.close()
                            else
                                @Suppress("DEPRECATION")
                                provider.release()
                        }

                        val dialog = AlertDialog.Builder(this)
                                .setTitle(R.string.entries_count_title)
                                .setMessage(getString(R.string.entries_count_message, androidEntries, processedEntries, "…"))
                                .setNeutralButton(android.R.string.ok) { dialog, _ ->
                                    dialog.dismiss()
                                }
                                .create()
                        val countJob = GlobalScope.launch {
                            class Count(var count: Int)

                            val latestAppId = getDecsync(info).latestAppId()
                            val countDecsync = Decsync<Count>(info.decsyncDir, info.syncType, info.collection, latestAppId)
                            countDecsync.addListener(emptyList()) { _, entry, count ->
                                if (!isActive) throw CancellationException()
                                if (!entry.value.isNull) {
                                    count.count++
                                }
                            }
                            val count = Count(0)
                            countDecsync.executeStoredEntriesForPathPrefix(listOf("resources"), count)
                            dialog.setMessage(getString(R.string.entries_count_message, androidEntries, processedEntries, "%d".format(count.count)))
                        }
                        dialog.setOnDismissListener {
                            countJob.cancel()
                        }
                        dialog.show()
                    }
                }
            }
            true
        }
        popup.show()

        // long click was handled
        true
    }

    private fun setCollectionInfo(info: CollectionInfo, key: JsonElement, value: JsonElement) {
        Log.d(TAG, "Set info for ${info.id} of key $key to value $value")
        getDecsync(info).setEntry(listOf("info"), key, value)
        info.getProviderClient(this)?.let { provider ->
            try {
                val extra = Extra(info, this, provider)
                when (info.type) {
                    CollectionInfo.Type.ADDRESS_BOOK ->
                        ContactDecsyncUtils.infoListener(emptyList(), Decsync.Entry(key, value), extra)
                    CollectionInfo.Type.CALENDAR ->
                        CalendarDecsyncUtils.infoListener(emptyList(), Decsync.Entry(key, value), extra)
                }
            } finally {
                if (Build.VERSION.SDK_INT >= 24)
                    provider.close()
                else
                    @Suppress("DEPRECATION")
                    provider.release()
            }
        }
        when (info.type) {
            CollectionInfo.Type.ADDRESS_BOOK -> loadBooks(info.decsyncDir)
            CollectionInfo.Type.CALENDAR -> loadCalendars(info.decsyncDir)
        }
    }


    /* LIST ADAPTERS */

    class AddressBookAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_address_book_item) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_address_book_item, parent, false)
            val info = getItem(position)!!
            val isChecked = info.isEnabled(context)

            val checked = v.findViewById<CheckBox>(R.id.checked)
            checked.isChecked = isChecked

            val tv = v.findViewById<TextView>(R.id.title)
            tv.text = info.name

            v.findViewById<ImageView>(R.id.action_overflow).setOnClickListener { view ->
                (context as? MainActivity)?.let {
                    it.onActionOverflowListener(view, info)
                }
            }

            return v
        }
    }

    class AddressBookUnknownAdapter(
            context: Context
    ): ArrayAdapter<Account>(context, R.layout.account_item_unknown) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_item_unknown, parent, false)
            val account = getItem(position)!!

            val checked = v.findViewById<CheckBox>(R.id.checked)
            checked.isChecked = true

            val tv = v.findViewById<TextView>(R.id.title)
            tv.text = account.name

            return v
        }
    }

    class CalendarAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_calendar_item) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_calendar_item, parent, false)
            val info = getItem(position)!!
            val isChecked = info.isEnabled(context)

            val checked = v.findViewById<CheckBox>(R.id.checked)
            checked.isChecked = isChecked

            val vColor = v.findViewById<View>(R.id.color)
            val decsyncInfo = Decsync.getStaticInfo(info.decsyncDir, info.syncType, info.collection)
            val color = decsyncInfo[JsonLiteral("color")]?.content
            vColor.visibility = color?.let {
                try { Color.parseColor(it) } catch (e: IllegalArgumentException) { null }
            }?.let {
                vColor.setBackgroundColor(it)
                View.VISIBLE
            } ?: View.INVISIBLE

            val tv = v.findViewById<TextView>(R.id.title)
            tv.text = info.name

            v.findViewById<ImageView>(R.id.action_overflow).setOnClickListener { view ->
                (context as? MainActivity)?.let {
                    it.onActionOverflowListener(view, info)
                }
            }

            return v
        }
    }

    class DecsyncIdName(val decsyncId: String, val name: String)
    class CalendarUnknownAdapter(
            context: Context
    ): ArrayAdapter<DecsyncIdName>(context, R.layout.account_item_unknown) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_item_unknown, parent, false)
            val item = getItem(position)!!

            val checked = v.findViewById<CheckBox>(R.id.checked)
            checked.isChecked = true

            val tv = v.findViewById<TextView>(R.id.title)
            tv.text = item.name

            return v
        }
    }
}
