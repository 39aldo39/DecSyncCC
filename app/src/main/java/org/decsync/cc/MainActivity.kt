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
import androidx.work.*
import at.bitfire.ical4android.AndroidTaskList
import at.bitfire.ical4android.TaskProvider
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.decsync.cc.calendars.COLUMN_NUM_PROCESSED_ENTRIES
import org.decsync.cc.calendars.CalendarDecsyncUtils
import org.decsync.cc.calendars.CalendarsInitWorker
import org.decsync.cc.contacts.ContactDecsyncUtils
import org.decsync.cc.contacts.ContactsInitWorker
import org.decsync.cc.contacts.KEY_NUM_PROCESSED_ENTRIES
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.cc.tasks.LocalTaskList
import org.decsync.cc.tasks.TasksDecsyncUtils
import org.decsync.cc.tasks.TasksInitWorker
import org.decsync.library.*
import org.dmfs.tasks.contract.TaskContract
import java.util.Random
import kotlin.math.min

const val TAG = "DecSyncCC"

/**
 * Address book permission requests have a requestCode in the range [100, 199)
 * Calendar requests in the range [200, 299)
 * Task list request in the range [300, 399)
 * The exclusive ends are used for invalid items
 */
const val PERMISSIONS_ADDRESS_BOOK_START = 100
const val PERMISSIONS_ADDRESS_BOOK_END = 199
const val PERMISSIONS_CALENDAR_START = 200
const val PERMISSIONS_CALENDAR_END = 299
const val PERMISSIONS_TASK_LIST_START = 300
const val PERMISSIONS_TASK_LIST_END = 399

@ExperimentalStdlibApi
class MainActivity: AppCompatActivity(), Toolbar.OnMenuItemClickListener, PopupMenu.OnMenuItemClickListener {

    private var decsyncDir: NativeFile? = null
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

        // Disable tasks when the app is not installed
        val authority = PrefUtils.getTasksAuthority(this)
        if (authority != null) {
            val providerName = TaskProvider.ProviderName.fromAuthority(authority)
            try {
                packageManager.getPackageInfo(providerName.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "Task app $providerName not installed")
                PrefUtils.putTasksAuthority(this, null)
            }
        }

        // Task lists toolbar
        task_lists_menu.inflateMenu(R.menu.task_list_actions)
        task_lists_menu.setOnMenuItemClickListener(this)

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
        address_books.adapter = CollectionAdapter(this)
        address_books.onItemClickListener = onCollectionClickListener

        address_books_unknown.adapter = CollectionUnknownAdapter(this)
        address_books_unknown.onItemClickListener = onCollectionUnknownListener

        calendars.adapter = CollectionAdapter(this)
        calendars.onItemClickListener = onCollectionClickListener

        calendars_unknown.adapter = CollectionUnknownAdapter(this)
        calendars_unknown.onItemClickListener = onCollectionUnknownListener

        task_lists.adapter = CollectionAdapter(this)
        task_lists.onItemClickListener = onCollectionClickListener

        task_lists_unknown.adapter = CollectionUnknownAdapter(this)
        task_lists_unknown.onItemClickListener = onCollectionUnknownListener
    }

    override fun onResume() {
        super.onResume()
        decsyncDir = if (!PrefUtils.getUseSaf(this) &&
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
            val decsyncTaskListIds = decsyncDir?.let(::loadTaskLists) ?: emptyList()
            loadTaskListsUnknown(decsyncTaskListIds)
        }
    }

    private fun loadBooks(decsyncDir: NativeFile): List<String> {
        Log.d(TAG, "Load address books")
        val decsyncIds = listDecsyncCollections(decsyncDir, "contacts")
        val collectionInfos = decsyncIds.mapNotNull { id ->
            val info = Decsync.getStaticInfo(decsyncDir, "contacts", id)
            val deleted = info[JsonPrimitive("deleted")]?.jsonPrimitive?.boolean ?: false
            if (!deleted) {
                val name = info[JsonPrimitive("name")]?.jsonPrimitive?.content ?: id
                AddressBookInfo(id, name)
            } else {
                null
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = address_books.adapter as CollectionAdapter
            adapter.clear()
            adapter.addAll(collectionInfos)
        }

        return decsyncIds
    }

    private fun loadBooksUnknown(decsyncIds: List<String>) {
        Log.d(TAG, "Load unknown address books")
        val accountManager = AccountManager.get(this)
        val infos = accountManager.getAccountsByType(getString(R.string.account_type_contacts)).map { account ->
            val bookId = accountManager.getUserData(account, "id")
            AddressBookInfo(bookId, account.name)
        }.filter { info ->
            info.id !in decsyncIds
        }

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = address_books_unknown.adapter as CollectionUnknownAdapter
            adapter.clear()
            adapter.addAll(infos)
            address_books_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun loadCalendars(decsyncDir: NativeFile): List<String> {
        Log.d(TAG, "Load calendars")
        val decsyncIds = listDecsyncCollections(decsyncDir, "calendars")
        val collectionInfos = decsyncIds.mapNotNull {
            val info = Decsync.getStaticInfo(decsyncDir, "calendars", it)
            val deleted = info[JsonPrimitive("deleted")]?.jsonPrimitive?.boolean ?: false
            if (!deleted) {
                val name = info[JsonPrimitive("name")]?.jsonPrimitive?.content ?: it
                val decsyncColor = info[JsonPrimitive("color")]?.jsonPrimitive?.content
                val color = decsyncColor?.let(Utils::parseColor)
                CalendarInfo(it, name, color)
            } else {
                null
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = calendars.adapter as CollectionAdapter
            adapter.clear()
            adapter.addAll(collectionInfos)
        }

        return decsyncIds
    }

    private fun loadCalendarsUnknown(decsyncIds: List<String>) {
        Log.d(TAG, "Load unknown calendars")
        val calendarsAccount = Account(PrefUtils.getCalendarAccountName(this), getString(R.string.account_type_calendars))
        val infos = mutableListOf<CalendarInfo>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
                try {
                    provider.query(syncAdapterUri(calendarsAccount, Calendars.CONTENT_URI),
                            arrayOf(Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR),
                            null, null, null)!!.use { cursor ->
                        while (cursor.moveToNext()) {
                            val decsyncId = cursor.getString(0)
                            val name = cursor.getString(1)
                            val color = cursor.getInt(2)
                            if (decsyncId !in decsyncIds) {
                                infos.add(CalendarInfo(decsyncId, name, color))
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
            val adapter = calendars_unknown.adapter as CollectionUnknownAdapter
            adapter.clear()
            adapter.addAll(infos)
            calendars_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun loadTaskLists(decsyncDir: NativeFile): List<String> {
        Log.d(TAG, "Load task lists")
        val decsyncIds = listDecsyncCollections(decsyncDir, "tasks")
        val collectionInfos = decsyncIds.mapNotNull {
            val info = Decsync.getStaticInfo(decsyncDir, "tasks", it)
            val deleted = info[JsonPrimitive("deleted")]?.jsonPrimitive?.boolean ?: false
            if (!deleted) {
                val name = info[JsonPrimitive("name")]?.jsonPrimitive?.content ?: it
                val color = info[JsonPrimitive("color")]?.jsonPrimitive?.content
                val colorInt = try {
                    color?.let(Color::parseColor)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Unknown color $color", e)
                    null
                }
                TaskListInfo(it, name, colorInt)
            } else {
                null
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = task_lists.adapter as CollectionAdapter
            adapter.clear()
            adapter.addAll(collectionInfos)
        }

        return decsyncIds
    }

    private fun loadTaskListsUnknown(decsyncIds: List<String>) {
        Log.d(TAG, "Load unknown task lists")
        val idAndNames = getUnknownTasks(decsyncIds)

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = task_lists_unknown.adapter as CollectionUnknownAdapter
            adapter.clear()
            adapter.addAll(idAndNames)
            task_lists_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun getUnknownTasks(decsyncIds: List<String>): List<TaskListInfo> {
        val infos = mutableListOf<TaskListInfo>()
        val tasksAccount = Account(PrefUtils.getTasksAccountName(this), getString(R.string.account_type_tasks))
        val authority = PrefUtils.getTasksAuthority(this) ?: return emptyList()
        val providerName = TaskProvider.ProviderName.fromAuthority(authority)
        for (permission in providerName.permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return emptyList()
            }
        }
        contentResolver.acquireContentProviderClient(authority)?.let { provider ->
            try {
                val taskProvider = TaskProvider.fromProviderClient(this, providerName, provider)
                val taskLists = AndroidTaskList.find(tasksAccount, taskProvider, LocalTaskList.Factory, null, null)
                for (taskList in taskLists) {
                    val decsyncId = taskList.syncId ?: continue
                    val name = taskList.name ?: continue
                    val color = taskList.color
                    if (decsyncId !in decsyncIds) {
                        infos.add(TaskListInfo(decsyncId, name, color))
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
        return infos
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
            in PERMISSIONS_TASK_LIST_START until PERMISSIONS_TASK_LIST_END -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    val position = requestCode - PERMISSIONS_TASK_LIST_START
                    val view = task_lists.getChildAt(position)
                    val id = task_lists.getItemIdAtPosition(position)
                    task_lists.performItemClick(view, position, id)
                }
            }
            PERMISSIONS_TASK_LIST_END -> {} // Invalid position
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

                val tasksAuthority = PrefUtils.getTasksAuthority(this)
                if (tasksAuthority != null) {
                    val tasksAccount = Account(PrefUtils.getTasksAccountName(this), getString(R.string.account_type_tasks))
                    ContentResolver.requestSync(tasksAccount, tasksAuthority, extras)
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
        val decsyncDir = decsyncDir
        when (item.itemId) {
            R.id.create_address_book -> {
                if (decsyncDir == null) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val input = EditText(this)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (name.isNotBlank()) {
                                val id = "colID%05d".format(Random().nextInt(100000))
                                val info = AddressBookInfo(id, name)
                                GlobalScope.launch {
                                    setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.create_calendar -> {
                if (decsyncDir == null) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val input = EditText(this)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (name.isNotBlank()) {
                                val id = "colID%05d".format(Random().nextInt(100000))
                                val info = CalendarInfo(id, name, null)
                                GlobalScope.launch {
                                    setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.create_task_list -> {
                if (decsyncDir == null) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val input = EditText(this)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (name.isNotBlank()) {
                                val id = "colID%05d".format(Random().nextInt(100000))
                                val info = TaskListInfo(id, name, null)
                                GlobalScope.launch {
                                    setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.change_calendar_colors -> {
                val packageInFdroid = "ch.ihdg.calendarcolor"
                val packageInPlay = "net.slintes.android.ccc.full"
                val intent = Utils.launchIntent(this, packageInFdroid)
                        ?: Utils.launchIntent(this, packageInPlay)
                        ?: run {
                            val fdroidInstalled = Utils.appInstalled(this, "org.fdroid.fdroid")
                            val packageName = if (fdroidInstalled) packageInFdroid else packageInPlay
                            Utils.installAppIntent(this, packageName)
                        }
                if (intent != null) {
                    startActivity(intent)
                }
            }
        }
        return false
    }

    private val onCollectionClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
        if (!view.isEnabled) return@OnItemClickListener
        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<CollectionInfo>
        val info = adapter.getItem(position)!!
        val nowChecked = !info.isEnabled(this)

        if (info is TaskListInfo) {
            val authority = PrefUtils.getTasksAuthority(this)
            if (authority == null) {
                val providerNames = mutableListOf<TaskProvider.ProviderName>()
                for (providerName in Utils.TASK_PROVIDERS) {
                    try {
                        packageManager.getPackageInfo(providerName.packageName, 0)
                        providerNames.add(providerName)
                    } catch (e: PackageManager.NameNotFoundException) {
                    }
                }
                when (providerNames.size) {
                    0 -> {
                        val names = Utils.TASK_PROVIDER_NAMES.map { getString(it) }.toTypedArray()
                        var providerName: TaskProvider.ProviderName? = null
                        AlertDialog.Builder(this)
                                .setTitle(R.string.no_task_app_title)
                                .setSingleChoiceItems(names, -1) { _, which ->
                                    providerName = Utils.TASK_PROVIDERS[which]
                                }
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    providerName?.let {
                                        Utils.installApp(this, it.packageName)
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        return@OnItemClickListener
                    }
                    1 -> {
                        val providerName = providerNames[0]
                        PrefUtils.putTasksAuthority(this, providerName.authority)
                    }
                    else -> {
                        val names = providerNames.map { it.toString() }.toTypedArray()
                        var providerName: TaskProvider.ProviderName? = null
                        AlertDialog.Builder(this)
                                .setTitle(R.string.choose_task_app_title)
                                .setSingleChoiceItems(names, -1) { _, which ->
                                    providerName = providerNames[which]
                                }
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    providerName?.let {
                                        PrefUtils.putTasksAuthority(this, it.authority)
                                        task_lists.performItemClick(view, position, id)
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        return@OnItemClickListener
                    }
                }
            }
        }

        val permissions = mutableListOf<String>()
        for (permission in info.getPermissions(this)) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(permission)
            }
        }
        if (permissions.isNotEmpty()) {
            val (startIndex, endIndex) = when (info) {
                is AddressBookInfo -> Pair(PERMISSIONS_ADDRESS_BOOK_START, PERMISSIONS_ADDRESS_BOOK_END)
                is CalendarInfo -> Pair(PERMISSIONS_CALENDAR_START, PERMISSIONS_CALENDAR_END)
                is TaskListInfo -> Pair(PERMISSIONS_TASK_LIST_START, PERMISSIONS_TASK_LIST_END)
            }
            val requestCode = min(startIndex + position, endIndex)
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), requestCode)
            return@OnItemClickListener
        }

        if (nowChecked) {
            info.create(this)
            val inputData = Data.Builder()
                    .putString(InitWorker.KEY_ID, info.id)
                    .putString(InitWorker.KEY_NAME, info.name)
                    .build()
            val workerClass = when (info) {
                is AddressBookInfo -> ContactsInitWorker::class.java
                is CalendarInfo -> CalendarsInitWorker::class.java
                is TaskListInfo -> TasksInitWorker::class.java
            }
            val workRequest = OneTimeWorkRequest.Builder(workerClass)
                    .setInputData(inputData)
                    .build()
            WorkManager.getInstance(this).enqueueUniqueWork("${info.notificationId}", ExistingWorkPolicy.REPLACE, workRequest)
        } else {
            WorkManager.getInstance(this).cancelUniqueWork("${info.notificationId}")
            info.remove(this)
        }
        adapter.notifyDataSetChanged()
    }

    private val onCollectionUnknownListener = AdapterView.OnItemClickListener { parent, view, position, _ ->
        if (!view.isEnabled) return@OnItemClickListener
        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<CollectionInfo>
        val info = adapter.getItem(position)!!
        info.remove(this)
        adapter.remove(info)
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
                                    GlobalScope.launch {
                                        setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> }
                            .show()
                }
                R.id.delete_collection -> {
                    AlertDialog.Builder(this)
                            .setTitle(getString(R.string.delete_collection_title, info.name))
                            .setPositiveButton(android.R.string.yes) { _, _ ->
                                GlobalScope.launch {
                                    setCollectionInfo(info, JsonPrimitive("deleted"), JsonPrimitive(true))
                                }
                            }
                            .setNegativeButton(android.R.string.no) { _, _ -> }
                            .show()
                }
                R.id.entries_count -> {
                    var androidEntries = 0
                    var processedEntries = 0
                    info.getProviderClient(this)?.let { provider ->
                        try {
                            when (info) {
                                is AddressBookInfo -> {
                                    processedEntries = AccountManager.get(this).getUserData(info.getAccount(this), KEY_NUM_PROCESSED_ENTRIES)?.toInt()
                                            ?: 0
                                    provider.query(syncAdapterUri(info.getAccount(this), RawContacts.CONTENT_URI),
                                            emptyArray(), "${RawContacts.DELETED}=0",
                                            null, null)!!.use { cursor ->
                                        androidEntries = cursor.count
                                    }
                                }
                                is CalendarInfo -> {
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
                                is TaskListInfo -> {
                                    val taskList = info.getTaskList(this)
                                    processedEntries = taskList?.numProcessedEntries ?: 0
                                    androidEntries = taskList?.queryTasks("${TaskContract.Tasks._DELETED}=0", null)?.size ?: 0
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

                            val latestAppId = getDecsync(info, this@MainActivity).latestAppId()
                            val decsyncDir = decsyncDir
                            val decsyncCount = if (decsyncDir != null) {
                                val countDecsync = Decsync<Count>(decsyncDir, info.syncType, info.id, latestAppId)
                                countDecsync.addListener(emptyList()) { _, entry, count ->
                                    if (!isActive) throw CancellationException()
                                    if (entry.value !is JsonNull) {
                                        count.count++
                                    }
                                }
                                val count = Count(0)
                                countDecsync.executeStoredEntriesForPathPrefix(listOf("resources"), count)
                                "%d".format(count.count)
                            } else {
                                "-"
                            }
                            dialog.setMessage(getString(R.string.entries_count_message, androidEntries, processedEntries, decsyncCount))
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
        val decsyncDir = decsyncDir ?: return
        getDecsync(info, this, decsyncDir).setEntry(listOf("info"), key, value)
        if (info.isEnabled(this)) {
            info.getProviderClient(this)?.let { provider ->
                try {
                    val extra = Extra(info, this, provider)
                    val infoListener = when (info) {
                        is AddressBookInfo -> ContactDecsyncUtils::infoListener
                        is CalendarInfo -> CalendarDecsyncUtils::infoListener
                        is TaskListInfo -> TasksDecsyncUtils::infoListener
                    }
                    infoListener(emptyList(), Decsync.Entry(key, value), extra)
                } finally {
                    if (Build.VERSION.SDK_INT >= 24)
                        provider.close()
                    else
                        @Suppress("DEPRECATION")
                        provider.release()
                }
            }
        }
        val loadCollections = when (info) {
            is AddressBookInfo -> ::loadBooks
            is CalendarInfo -> ::loadCalendars
            is TaskListInfo -> ::loadTaskLists
        }
        loadCollections(decsyncDir)
    }


    /* LIST ADAPTERS */

    class CollectionAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_item) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_item, parent, false)
            val info = getItem(position)!!
            val isChecked = info.isEnabled(context)

            val checked = v.findViewById<CheckBox>(R.id.checked)
            checked.isChecked = isChecked

            val vColor = v.findViewById<View>(R.id.color)
            vColor.visibility = info.color?.let {
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

    class CollectionUnknownAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_item_unknown) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_item_unknown, parent, false)
            val info = getItem(position)!!

            val checked = v.findViewById<CheckBox>(R.id.checked)
            checked.isChecked = true

            val tv = v.findViewById<TextView>(R.id.title)
            tv.text = info.name

            return v
        }
    }
}
