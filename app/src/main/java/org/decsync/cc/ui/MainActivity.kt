/*
 * Copyright © 2018 Aldo Gunsing.
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package org.decsync.cc.ui

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.ContactsContract.RawContacts
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import at.bitfire.ical4android.AndroidTaskList
import at.bitfire.ical4android.TaskProvider
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_intro_directory.*
import kotlinx.android.synthetic.main.collections.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.decsync.cc.*
import org.decsync.cc.BuildConfig
import org.decsync.cc.R
import org.decsync.cc.calendars.CalendarsListeners
import org.decsync.cc.calendars.CalendarsUtils
import org.decsync.cc.calendars.CalendarsWorker
import org.decsync.cc.contacts.*
import org.decsync.cc.databinding.ActivityMainBinding
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.cc.tasks.LocalTaskList
import org.decsync.cc.tasks.TasksListeners
import org.decsync.cc.tasks.TasksWorker
import org.decsync.library.*
import org.dmfs.tasks.contract.TaskContract
import java.util.*
import kotlin.math.min

private const val TAG = "DecSyncCC"

/**
 * Address book permission requests have a requestCode in the range [100, 199)
 * Calendar requests in the range [200, 299)
 * Task list request in the range [300, 399)
 * The exclusive ends are used for invalid items
 */
private const val PERMISSIONS_ADDRESS_BOOK_START = 100
private const val PERMISSIONS_ADDRESS_BOOK_END = 199
private const val PERMISSIONS_CALENDAR_START = 200
private const val PERMISSIONS_CALENDAR_END = 299
private const val PERMISSIONS_TASK_LIST_START = 300
private const val PERMISSIONS_TASK_LIST_END = 399

private const val REQUEST_ADD_DIRECTORY = 1

@ExperimentalStdlibApi
class MainActivity: AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, Toolbar.OnMenuItemClickListener, PopupMenu.OnMenuItemClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var actionBarDrawerToggle: ActionBarDrawerToggle
    private var decsyncDir: DecsyncDirectory? = null
    private var nativeFile: NativeFile? = null
    private var correctPermissions = false

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        PrefUtils.notifyTheme(this)
        super.onCreate(savedInstanceState)

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

        // Main layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appBarMain.toolbar)
        val navView = binding.navView
        val drawerLayout = binding.drawerLayout
        navView.setNavigationItemSelectedListener(this)
        actionBarDrawerToggle = ActionBarDrawerToggle(this, drawerLayout, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerLayout.addDrawerListener(actionBarDrawerToggle)
        actionBarDrawerToggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Address books toolbar
        contacts_menu.inflateMenu(R.menu.address_book_actions)
        contacts_menu.setOnMenuItemClickListener(this)

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
        if (Build.VERSION.SDK_INT >= 23 && PrefUtils.getHintBatteryOptimizations(this)) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID)) {
                AlertDialog.Builder(this)
                        .setTitle(R.string.startup_battery_optimization)
                        .setMessage(R.string.startup_battery_optimization_message)
                        .setPositiveButton(R.string.startup_battery_optimization_disable) @TargetApi(23) { _, _ ->
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + BuildConfig.APPLICATION_ID))
                            startActivity(intent)
                        }
                        .setNeutralButton(R.string.startup_not_now) { _, _ -> }
                        .setNegativeButton(R.string.startup_dont_show_again) { _, _ ->
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

        GlobalScope.launch {
            // Check for upgrade
            PrefUtils.checkAppUpgrade(this@MainActivity)
            if (PrefUtils.getUpdateForcesSaf(this@MainActivity)) {
                val intent = Intent(this@MainActivity, SafUpdateActivity::class.java)
                startActivity(intent)
                finish()
                return@launch
            }
            loadNavigation()
            loadDecsyncDir()
            loadCollections()
        }
    }

    suspend fun loadDecsyncDir() {
        decsyncDir = try {
            val dirId = PrefUtils.getSelectedDir(this)
            if (dirId == -1L) {
                val intent = Intent(this, IntroActivity::class.java)
                startActivity(intent)
                finish()
                decsyncDir = null
                nativeFile = null
                return
            }
            App.db.decsyncDirectoryDao().find(dirId) ?: throw Exception("Unknown DecSync directory")
        } catch (e: Exception) {
            Utils.showBasicDialog(this, "DecSync", e.message)
            null
        }
        correctPermissions = PrefUtils.getUseSaf(this) ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (correctPermissions) {
            nativeFile = try {
                decsyncDir?.getNativeFile(this)?.also(::checkDecsyncInfo)
            } catch (e: Exception) {
                Utils.showBasicDialog(this, "DecSync", e.message)
                null
            }
        }
    }

    suspend fun loadNavigation() {
        val dirId = PrefUtils.getSelectedDir(this@MainActivity)
        val decsyncDirs = App.db.decsyncDirectoryDao().all()
        CoroutineScope(Dispatchers.Main).launch {
            val navView = binding.navView
            val directoriesMenu = navView.menu.findItem(R.id.nav_directories_container).subMenu
            directoriesMenu.clear()
            var checkedId: Int? = null
            for (decsyncDir in decsyncDirs) {
                val title = decsyncDir.name
                directoriesMenu.add(R.id.nav_directories, decsyncDir.id.toInt(), Menu.NONE, title).apply {
                    setIcon(R.drawable.folder)
                    isCheckable = true
                    if (decsyncDir.id == dirId) {
                        checkedId = itemId
                        binding.appBarMain.toolbar.title = getString(R.string.title_activity_main_with_dir, title)
                    }
                }
            }
            val addDirectoryTitle = getString(R.string.nav_directories_add)
            directoriesMenu.add(R.id.nav_directories, R.id.nav_directories_add, Menu.NONE, addDirectoryTitle).apply {
                setIcon(R.drawable.ic_add_light)
                isCheckable = false
            }
            checkedId?.let { navView.setCheckedItem(it) }
        }
    }

    fun loadCollections() {
        clearCollections()
        val decsyncAddressBookIds = loadBooks()
        loadBooksUnknown(decsyncAddressBookIds)
        val decsyncCalendarIds = loadCalendars()
        loadCalendarsUnknown(decsyncCalendarIds)
        val decsyncTaskListIds = loadTaskLists()
        loadTaskListsUnknown(decsyncTaskListIds)
    }

    fun clearCollections() {
        CoroutineScope(Dispatchers.Main).launch {
            (address_books.adapter as CollectionAdapter).clear()
            address_books_empty.visibility = View.GONE
            address_books_cardview_unknown.visibility = View.GONE

            (calendars.adapter as CollectionAdapter).clear()
            calendars_empty.visibility = View.GONE
            calendars_cardview_unknown.visibility = View.GONE

            (task_lists.adapter as CollectionAdapter).clear()
            task_lists_empty.visibility = View.GONE
            task_lists_cardview_unknown.visibility = View.GONE
        }
    }

    private fun loadBooks(): List<String> {
        Log.d(TAG, "Load address books")
        val infos = getBooks()

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = address_books.adapter as CollectionAdapter
            adapter.clear()
            adapter.addAll(infos)
            address_books_empty.visibility = if (adapter.isEmpty) View.VISIBLE else View.GONE
        }

        return infos.map { it.id }
    }

    private fun getBooks(): List<AddressBookInfo> {
        val decsyncDir = decsyncDir ?: return emptyList()
        val nativeFile = nativeFile ?: return emptyList()
        val decsyncIds = listDecsyncCollections(nativeFile, "contacts")
        return decsyncIds.mapNotNull { id ->
            val info = Decsync.getStaticInfo(nativeFile, "contacts", id)
            val deleted = info[JsonPrimitive("deleted")]?.jsonPrimitive?.boolean ?: false
            if (deleted && !PrefUtils.getShowDeletedCollections(this)) {
                null
            } else {
                val name = info[JsonPrimitive("name")]?.jsonPrimitive?.content ?: id
                AddressBookInfo(decsyncDir, id, name, deleted)
            }
        }
    }

    private fun loadBooksUnknown(decsyncIds: List<String>) {
        Log.d(TAG, "Load unknown address books")
        val infos = getUnknownBooks(decsyncIds)

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = address_books_unknown.adapter as CollectionUnknownAdapter
            adapter.clear()
            adapter.addAll(infos)
            address_books_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun getUnknownBooks(decsyncIds: List<String>): List<AddressBookInfo> {
        val decsyncDir = decsyncDir ?: return emptyList()
        val accountManager = AccountManager.get(this)
        val infos = accountManager.getAccountsByType(getString(R.string.account_type_contacts)).mapNotNull { account ->
            val dirIdAccount = accountManager.getUserData(account, AddressBookInfo.KEY_DECSYNC_DIR_ID).toLong()
            if (dirIdAccount == decsyncDir.id) {
                val bookId = accountManager.getUserData(account, AddressBookInfo.KEY_COLLECTION_ID)
                val name = accountManager.getUserData(account, AddressBookInfo.KEY_NAME)
                AddressBookInfo(decsyncDir, bookId, name, false)
            } else {
                null
            }
        }.filter { info ->
            info.id !in decsyncIds
        }
        return infos
    }

    private fun loadCalendars(): List<String> {
        Log.d(TAG, "Load calendars")
        val infos = getCalendars()

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = calendars.adapter as CollectionAdapter
            adapter.clear()
            adapter.addAll(infos)
            calendars_empty.visibility = if (adapter.isEmpty) View.VISIBLE else View.GONE
        }

        return infos.map { it.id }
    }

    private fun getCalendars(): List<CalendarInfo> {
        val decsyncDir = decsyncDir ?: return emptyList()
        val nativeFile = nativeFile ?: return emptyList()
        val decsyncIds = listDecsyncCollections(nativeFile, "calendars")
        return decsyncIds.mapNotNull {
            val info = Decsync.getStaticInfo(nativeFile, "calendars", it)
            val deleted = info[JsonPrimitive("deleted")]?.jsonPrimitive?.boolean ?: false
            if (deleted && !PrefUtils.getShowDeletedCollections(this)) {
                null
            } else {
                val name = info[JsonPrimitive("name")]?.jsonPrimitive?.content ?: it
                val decsyncColor = info[JsonPrimitive("color")]?.jsonPrimitive?.content
                val color = decsyncColor?.let(Utils::parseColor)
                CalendarInfo(decsyncDir, it, name, color, deleted)
            }
        }
    }

    private fun loadCalendarsUnknown(decsyncIds: List<String>) {
        Log.d(TAG, "Load unknown calendars")
        val infos = getUnknownCalendars(decsyncIds)

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = calendars_unknown.adapter as CollectionUnknownAdapter
            adapter.clear()
            adapter.addAll(infos)
            calendars_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun getUnknownCalendars(decsyncIds: List<String>): List<CalendarInfo> {
        val decsyncDir = decsyncDir ?: return emptyList()
        val calendarsAccount = decsyncDir.getCalendarAccount(this)
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
                                infos.add(CalendarInfo(decsyncDir, decsyncId, name, color, false))
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
        return infos
    }

    private fun loadTaskLists(): List<String> {
        Log.d(TAG, "Load task lists")
        val infos = getTaskLists()

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = task_lists.adapter as CollectionAdapter
            adapter.clear()
            adapter.addAll(infos)
            task_lists_empty.visibility = if (adapter.isEmpty) View.VISIBLE else View.GONE
        }

        return infos.map { it.id }
    }

    private fun getTaskLists(): List<TaskListInfo> {
        val decsyncDir = decsyncDir ?: return emptyList()
        val nativeFile = nativeFile ?: return emptyList()
        val decsyncIds = listDecsyncCollections(nativeFile, "tasks")
        return decsyncIds.mapNotNull {
            val info = Decsync.getStaticInfo(nativeFile, "tasks", it)
            val deleted = info[JsonPrimitive("deleted")]?.jsonPrimitive?.boolean ?: false
            if (deleted && !PrefUtils.getShowDeletedCollections(this)) {
                null
            } else {
                val name = info[JsonPrimitive("name")]?.jsonPrimitive?.content ?: it
                val color = info[JsonPrimitive("color")]?.jsonPrimitive?.content
                val colorInt = try {
                    color?.let(Color::parseColor)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Unknown color $color", e)
                    null
                }
                TaskListInfo(decsyncDir, it, name, colorInt, deleted)
            }
        }
    }

    private fun loadTaskListsUnknown(decsyncIds: List<String>) {
        Log.d(TAG, "Load unknown task lists")
        val infos = getUnknownTasks(decsyncIds)

        CoroutineScope(Dispatchers.Main).launch {
            val adapter = task_lists_unknown.adapter as CollectionUnknownAdapter
            adapter.clear()
            adapter.addAll(infos)
            task_lists_cardview_unknown.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun getUnknownTasks(decsyncIds: List<String>): List<TaskListInfo> {
        val infos = mutableListOf<TaskListInfo>()
        val decsyncDir = decsyncDir ?: return emptyList()
        val tasksAccount = decsyncDir.getTaskListAccount(this)
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
                        infos.add(TaskListInfo(decsyncDir, decsyncId, name, color, false))
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ADD_DIRECTORY) {
            if (resultCode == Activity.RESULT_OK) {
                GlobalScope.launch {
                    loadNavigation()
                    loadDecsyncDir()
                    loadCollections()
                }
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_directories_add -> {
                val intent = Intent(this, AddDirectoryActivity::class.java)
                startActivityForResult(intent, REQUEST_ADD_DIRECTORY)
            }
            R.id.nav_settings -> {
                val intent = Intent(this, GeneralPrefsActivity::class.java)
                startActivity(intent)
                binding.drawerLayout.close()
            }
            else -> { // DecSync directory
                PrefUtils.putSelectedDir(this, item.itemId.toLong())
                binding.appBarMain.toolbar.title = getString(R.string.title_activity_main_with_dir, item.title)
                binding.drawerLayout.close()
                GlobalScope.launch {
                    loadDecsyncDir()
                    loadCollections()
                }
            }
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        menu.findItem(R.id.show_deleted_collections).isChecked = PrefUtils.getShowDeletedCollections(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (actionBarDrawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        when (item.itemId) {
            R.id.sync_now -> {
                GlobalScope.launch {
                    loadDecsyncDir()
                    loadCollections()
                    ContactsWorker.enqueueAll(this@MainActivity)
                    CalendarsWorker.enqueueAll(this@MainActivity)
                    TasksWorker.enqueueAll(this@MainActivity)
                }

                Snackbar.make(
                    findViewById(R.id.collections),
                    R.string.account_synchronizing_now,
                    Snackbar.LENGTH_LONG
                ).show()
            }
            R.id.show_deleted_collections -> {
                val newValue = !PrefUtils.getShowDeletedCollections(this)
                PrefUtils.putShowDeletedCollections(this, newValue)
                item.isChecked = newValue
                GlobalScope.launch {
                    loadCollections()
                }
            }
            R.id.decsync_dir_show -> {
                val decsyncDir = decsyncDir ?: return true
                if (PrefUtils.getUseSaf(this)) {
                    // TODO: crashes on SDK 26
                    // Works on SDK >= 29, so it is fine for now
                    val intent = Intent(Intent.ACTION_VIEW)
                    val uri = Uri.parse(decsyncDir.directory)
                    intent.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    startActivity(intent)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(decsyncDir.name)
                        .setMessage(decsyncDir.directory)
                        .setNeutralButton(android.R.string.ok) { _, _ -> }
                        .show()
                }
            }
            R.id.decsync_dir_remove -> {
                val decsyncDir = decsyncDir ?: return true
                GlobalScope.launch {
                    if (!Utils.checkCollectionEnabled(this@MainActivity, decsyncDir)) {
                        CoroutineScope(Dispatchers.Main).launch {
                            AlertDialog.Builder(this@MainActivity)
                                .setMessage(getString(R.string.remove_decsync_dir_title, decsyncDir.name))
                                .setPositiveButton(android.R.string.yes) { _, _ ->
                                    GlobalScope.launch {
                                        App.db.decsyncDirectoryDao().delete(decsyncDir)
                                        val dirId = App.db.decsyncDirectoryDao().all().firstOrNull()?.id ?: -1
                                        PrefUtils.putSelectedDir(this@MainActivity, dirId)
                                        loadNavigation()
                                        loadDecsyncDir()
                                        loadCollections()
                                    }
                                }
                                .setNegativeButton(android.R.string.no) { _, _ -> }
                                .show()
                        }
                    }
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val decsyncDir = decsyncDir ?: return false
        when (item.itemId) {
            R.id.create_address_book -> {
                if (!correctPermissions) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val adapter = address_books.adapter as CollectionAdapter
                var defaultName = getString(R.string.account_collection_name_default)
                for (i in 2 .. 100) {
                    if (adapter.all { it.name != defaultName }) break
                    defaultName = getString(R.string.account_collection_name_default_n, i)
                }
                val input = EditText(this)
                input.setText(defaultName)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (name.isNotBlank()) {
                                val id = UUID.randomUUID().toString()
                                val info = AddressBookInfo(decsyncDir, id, name, false)
                                GlobalScope.launch {
                                    setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.create_calendar -> {
                if (!correctPermissions) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val adapter = calendars.adapter as CollectionAdapter
                var defaultName = getString(R.string.account_collection_name_default)
                for (i in 2 .. 100) {
                    if (adapter.all { it.name != defaultName }) break
                    defaultName = getString(R.string.account_collection_name_default_n, i)
                }
                val input = EditText(this)
                input.setText(defaultName)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (name.isNotBlank()) {
                                ColorPickerDialogBuilder.with(this).apply {
                                    wheelType(ColorPickerView.WHEEL_TYPE.CIRCLE)
                                    density(7)
                                    lightnessSliderOnly()
                                    setPositiveButton(android.R.string.ok) { _, color, _ ->
                                        GlobalScope.launch {
                                            val id = UUID.randomUUID().toString()
                                            val info = CalendarInfo(decsyncDir, id, name, color, false)
                                            setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                            setCollectionInfo(info, JsonPrimitive("color"), JsonPrimitive(Utils.colorToString(color)))
                                        }
                                    }
                                    setNegativeButton(android.R.string.cancel) { _, _ -> }
                                }.build().show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.create_task_list -> {
                if (!correctPermissions) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                    return false
                }
                val adapter = task_lists.adapter as CollectionAdapter
                var defaultName = getString(R.string.account_collection_name_default)
                for (i in 2 .. 100) {
                    if (adapter.all { it.name != defaultName }) break
                    defaultName = getString(R.string.account_collection_name_default_n, i)
                }
                val input = EditText(this)
                input.setText(defaultName)
                AlertDialog.Builder(this)
                        .setTitle(R.string.create_collection_title)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString()
                            if (name.isNotBlank()) {
                                ColorPickerDialogBuilder.with(this).apply {
                                    wheelType(ColorPickerView.WHEEL_TYPE.CIRCLE)
                                    density(7)
                                    lightnessSliderOnly()
                                    setPositiveButton(android.R.string.ok) { _, color, _ ->
                                        GlobalScope.launch {
                                            val id = UUID.randomUUID().toString()
                                            val info = TaskListInfo(decsyncDir, id, name, color, false)
                                            setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                            setCollectionInfo(info, JsonPrimitive("color"), JsonPrimitive(Utils.colorToString(color)))
                                        }
                                    }
                                    setNegativeButton(android.R.string.cancel) { _, _ -> }
                                }.build().show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
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
                val names = mutableListOf<String>()
                for ((providerName, name) in Utils.TASK_PROVIDERS.zip(Utils.TASK_PROVIDER_NAMES)) {
                    try {
                        packageManager.getPackageInfo(providerName.packageName, 0)
                        providerNames.add(providerName)
                        names.add(getString(name))
                    } catch (e: PackageManager.NameNotFoundException) {
                    }
                }
                when (providerNames.size) {
                    0 -> {
                        val allNames = Utils.TASK_PROVIDER_NAMES.map { getString(it) }
                        var providerName: TaskProvider.ProviderName? = null
                        AlertDialog.Builder(this)
                                .setTitle(R.string.no_task_app_title)
                                .setSingleChoiceItems(allNames.toTypedArray(), -1) { _, which ->
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
                        var providerName: TaskProvider.ProviderName? = null
                        AlertDialog.Builder(this)
                                .setTitle(R.string.choose_task_app_title)
                                .setSingleChoiceItems(names.toTypedArray(), -1) { _, which ->
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
            PrefUtils.putIsInitSync(this, info, true)
            info.create(this)
            CollectionWorker.enqueue(this, info)
        } else {
            CollectionWorker.dequeue(this, info)
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
        if (info is AddressBookInfo) {
            popup.menu.findItem(R.id.change_color_collection)?.isVisible = false
        }

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
                                if (name.isNotBlank() && name != info.name) {
                                    GlobalScope.launch {
                                        setCollectionInfo(info, JsonPrimitive("name"), JsonPrimitive(name))
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> }
                            .show()
                }
                R.id.change_color_collection -> {
                    ColorPickerDialogBuilder.with(this).apply {
                        info.color?.let { initialColor(it) }
                        wheelType(ColorPickerView.WHEEL_TYPE.CIRCLE)
                        density(7)
                        lightnessSliderOnly()
                        setPositiveButton(android.R.string.ok) { _, color, _ ->
                            if (color != info.color) {
                                GlobalScope.launch {
                                    setCollectionInfo(info, JsonPrimitive("color"), JsonPrimitive(Utils.colorToString(color)))
                                }
                            }
                        }
                        setNegativeButton(android.R.string.cancel) { _, _ -> }
                    }.build().show()
                }
                R.id.delete_collection -> {
                    AlertDialog.Builder(this)
                            .setMessage(getString(R.string.delete_collection_title, info.name))
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

                    val permissions = mutableListOf<String>()
                    for (permission in info.getPermissions(this)) {
                        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                            permissions.add(permission)
                        }
                    }
                    if (permissions.isEmpty()) {
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
                                                arrayOf(Calendars._ID, CalendarsUtils.COLUMN_NUM_PROCESSED_ENTRIES), "${Calendars.NAME}=?",
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
                                        androidEntries = taskList?.queryTasks("${TaskContract.Tasks._DELETED}=0", null)?.size
                                                ?: 0
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

                    val dialog = AlertDialog.Builder(this)
                            .setTitle(R.string.entries_count_title)
                            .setMessage(getString(R.string.entries_count_message, androidEntries, processedEntries, "…"))
                            .setNeutralButton(android.R.string.ok) { dialog, _ ->
                                dialog.dismiss()
                            }
                            .create()
                    val countJob = GlobalScope.launch {
                        class Count(var count: Int)

                        val nativeFile = nativeFile
                        val decsyncCount = if (nativeFile != null) {
                            val latestAppId = getDecsync(info, this@MainActivity, nativeFile).latestAppId()
                            val countDecsync = Decsync<Count>(nativeFile, info.syncType, info.id, latestAppId)
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
                R.id.manage_decsync_data -> {
                    val typedValue = TypedValue()
                    var normalColor = Color.GRAY // Default value
                    if (theme.resolveAttribute(android.R.attr.colorForeground, typedValue, true)) {
                        normalColor = typedValue.data
                    }
                    val params = DecsyncPrefUtils.Params(
                            ownAppId = PrefUtils.getOwnAppId(this),
                            colorNormal = normalColor
                    )
                    val nativeFile = nativeFile
                    if (nativeFile != null) {
                        DecsyncPrefUtils.manageDecsyncData(this, nativeFile, info.syncType, info.id, params)
                    }
                }
            }
            true
        }
        popup.show()

        // long click was handled
        true
    }


    private val onDeletedActionOverflowListener = { anchor: View, info: CollectionInfo ->
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.inflate(R.menu.account_collection_operations_deleted)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.restore_collection -> {
                    GlobalScope.launch {
                        setCollectionInfo(info, JsonPrimitive("deleted"), JsonPrimitive(false))
                    }
                }
                R.id.perm_delete_collection -> {
                    AlertDialog.Builder(this)
                            .setMessage(getString(R.string.perm_delete_collection_title, info.name))
                            .setPositiveButton(android.R.string.yes) { _, _ ->
                                val nativeFile = nativeFile
                                if (nativeFile != null) {
                                    DecsyncPrefUtils.permDeleteCollectionUsingWorker(this, nativeFile, info.syncType, info.id)
                                    val adapter = when (info) {
                                        is AddressBookInfo -> address_books.adapter
                                        is CalendarInfo -> calendars.adapter
                                        is TaskListInfo -> task_lists.adapter
                                    } as CollectionAdapter
                                    adapter.remove(info)
                                }
                            }
                            .setNegativeButton(android.R.string.no) { _, _ -> }
                            .show()
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
        val nativeFile = nativeFile ?: return
        getDecsync(info, this, nativeFile).setEntry(listOf("info"), key, value)
        if (info.isEnabled(this)) {
            info.getProviderClient(this)?.let { provider ->
                try {
                    val extra = Extra(info, this, provider)
                    val infoListener = when (info) {
                        is AddressBookInfo -> ContactsListeners::infoListener
                        is CalendarInfo -> CalendarsListeners::infoListener
                        is TaskListInfo -> TasksListeners::infoListener
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
        val loadCollectionType = when (info) {
            is AddressBookInfo -> ::loadBooks
            is CalendarInfo -> ::loadCalendars
            is TaskListInfo -> ::loadTaskLists
        }
        loadCollectionType()
    }


    /* LIST ADAPTERS */

    class CollectionAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_item) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_item, parent, false)
            val info = getItem(position)!!
            val isChecked = !info.deleted && info.isEnabled(context)

            v.isEnabled = !info.deleted

            val checked = v.findViewById<CheckBox>(R.id.checked)
            checked.isChecked = isChecked
            checked.isEnabled = !info.deleted

            val vColor = v.findViewById<View>(R.id.color)
            vColor.visibility = info.color?.let {
                vColor.setBackgroundColor(it)
                View.VISIBLE
            } ?: View.INVISIBLE

            val tv = v.findViewById<TextView>(R.id.title)
            tv.text = info.name
            tv.isEnabled = !info.deleted

            v.findViewById<ImageView>(R.id.action_overflow).setOnClickListener { view ->
                (context as? MainActivity)?.let {
                    val listener = if (info.deleted) it.onDeletedActionOverflowListener else it.onActionOverflowListener
                    listener(view, info)
                }
            }

            return v
        }

        fun any(predicate: (CollectionInfo) -> Boolean): Boolean {
            for (i in 0 until count) {
                if (predicate(getItem(i)!!)) return true
            }
            return false
        }

        fun all(predicate: (CollectionInfo) -> Boolean): Boolean = !any { !predicate(it) }
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
