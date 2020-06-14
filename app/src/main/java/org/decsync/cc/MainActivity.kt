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
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.AsyncTask
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.util.Log
import android.view.*
import android.widget.*
import at.bitfire.ical4android.AndroidCalendar
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonLiteral
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.content
import org.decsync.cc.calendars.COLUMN_NUM_PROCESSED_ENTRIES
import org.decsync.cc.calendars.CalendarDecsyncUtils
import org.decsync.cc.calendars.CalendarDecsyncUtils.addColor
import org.decsync.cc.contacts.ContactDecsyncUtils
import org.decsync.cc.contacts.KEY_NUM_PROCESSED_ENTRIES
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.*
import java.io.File
import java.util.Random

const val TAG = "DecSyncCC"

@ExperimentalStdlibApi
class MainActivity: AppCompatActivity(), Toolbar.OnMenuItemClickListener, PopupMenu.OnMenuItemClickListener {

    private var error = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentAppVersion = 1
        val appVersion = PrefUtils.getAppVersion(this)
        if (appVersion != currentAppVersion) {
            PrefUtils.putAppVersion(this, currentAppVersion)
        }

        setContentView(R.layout.activity_main)

        // Address books toolbar
        contacts_menu.inflateMenu(R.menu.address_book_actions)
        contacts_menu.setOnMenuItemClickListener(this)

        // Calendars toolbar
        val calendarsAccount = Account(getString(R.string.account_name_calendars), getString(R.string.account_type_calendars))
        val success = AccountManager.get(this).addAccountExplicitly(calendarsAccount, null, null)
        if (success) {
            ContentResolver.setSyncAutomatically(calendarsAccount, CalendarContract.AUTHORITY, true)
            ContentResolver.addPeriodicSync(calendarsAccount, CalendarContract.AUTHORITY, Bundle(), 60 * 60)
        }
        calendars_menu.inflateMenu(R.menu.calendar_actions)
        calendars_menu.setOnMenuItemClickListener(this)

        // Ask for permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            var permissions = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.READ_CONTACTS
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.WRITE_CONTACTS
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.READ_CALENDAR
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.WRITE_CALENDAR
            }
            ActivityCompat.requestPermissions(this, permissions, 0)
        }

        // Ask for exception to App Standby
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
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return
        try {
            checkDecsyncInfo(PrefUtils.getDecsyncDir(this))
        } catch (e: DecsyncException) {
            error = true
            AlertDialog.Builder(this)
                    .setTitle("DecSync")
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .show()
            return
        }

        loadBooks()
        loadCalendars()
    }

    private fun loadBooks() {
        val adapter = AddressBookAdapter(this)
        val decsyncDir = PrefUtils.getDecsyncDir(this)

        adapter.clear()
        adapter.addAll(
                listDecsyncCollections(decsyncDir, "contacts").mapNotNull {
                    val info = Decsync.getStaticInfo(decsyncDir, "contacts", it)
                    val deleted = info[JsonLiteral("deleted")]?.boolean ?: false
                    if (!deleted) {
                        val name = info[JsonLiteral("name")]?.content ?: it
                        CollectionInfo(CollectionInfo.Type.ADDRESS_BOOK, it, name, this)
                    } else {
                        null
                    }
                }
        )

        address_books.adapter = adapter
        address_books.onItemClickListener = onItemClickListener
    }

    private fun loadCalendars() {
        val adapter = CalendarAdapter(this)
        val decsyncDir = PrefUtils.getDecsyncDir(this)

        adapter.clear()
        adapter.addAll(
                listDecsyncCollections(decsyncDir, "calendars").mapNotNull {
                    val info = Decsync.getStaticInfo(decsyncDir, "calendars", it)
                    val deleted = info[JsonLiteral("deleted")]?.boolean ?: false
                    if (!deleted) {
                        val name = info[JsonLiteral("name")]?.content ?: it
                        CollectionInfo(CollectionInfo.Type.CALENDAR, it, name, this)
                    } else {
                        null
                    }
                }
        )

        calendars.adapter = adapter
        calendars.onItemClickListener = onItemClickListener
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sync_now -> {
                val extras = Bundle()
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) // Manual sync
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true) // Run immediately (don't queue)

                val calendarsAuthority = CalendarContract.AUTHORITY
                val calendarsAccount = Account(getString(R.string.account_name_calendars), getString(R.string.account_type_calendars))
                ContentResolver.requestSync(calendarsAccount, calendarsAuthority, extras)

                val contactsAuthority = ContactsContract.AUTHORITY
                val count = address_books.adapter.count
                for (position in 0 until count) {
                    val info = address_books.adapter.getItem(position) as CollectionInfo
                    if (!info.isEnabled(this)) continue
                    val account = info.getAccount(this)
                    ContentResolver.requestSync(account, contactsAuthority, extras)
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
                var permissions = emptyArray<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.READ_CONTACTS
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.WRITE_CONTACTS
                }
                if (permissions.isNotEmpty()) {
                    ActivityCompat.requestPermissions(this, permissions, 0)
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
                                val decsync = Decsync<Unit>(info.decsyncDir, info.syncType, info.collection, ownAppId)
                                decsync.setEntry(listOf("info"), JsonLiteral("name"), JsonLiteral(name))
                                loadBooks()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
            R.id.create_calendar -> {
                var permissions = emptyArray<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.READ_CALENDAR
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    permissions += Manifest.permission.WRITE_CALENDAR
                }
                if (permissions.isNotEmpty()) {
                    ActivityCompat.requestPermissions(this, permissions, 0)
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
                                val decsync = Decsync<Unit>(info.decsyncDir, info.syncType, info.collection, ownAppId)
                                decsync.setEntry(listOf("info"), JsonLiteral("name"), JsonLiteral(name))
                                loadCalendars()
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
                        ActivityCompat.requestPermissions(this, permissions, 0)
                        return@OnItemClickListener
                    }
                    val bundle = Bundle()
                    bundle.putString("id", info.id)
                    AccountManager.get(this).addAccountExplicitly(account, null, bundle)
                    ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
                    ContentResolver.addPeriodicSync(account, ContactsContract.AUTHORITY, Bundle(), 60 * 60)
                    AsyncTask.execute {
                        val builder = initSyncNotificationBuilder(this).apply {
                            setSmallIcon(R.drawable.ic_notification)
                            setContentTitle(getString(R.string.notification_adding_contacts, info.name))
                        }
                        with(NotificationManagerCompat.from(this)) {
                            notify(info.notificationId, builder.build())
                        }
                        contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)?.let { provider ->
                            try {
                                val decsync = getDecsync(info)
                                val extra = Extra(info, this, provider)
                                setNumProcessedEntries(extra, 0)
                                decsync.initStoredEntries()
                                decsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
                            } finally {
                                if (Build.VERSION.SDK_INT >= 24)
                                    provider.close()
                                else
                                    @Suppress("DEPRECATION")
                                    provider.release()
                            }
                        }
                        with(NotificationManagerCompat.from(this)) {
                            cancel(info.notificationId)
                        }
                    }
                } else {
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
                    ActivityCompat.requestPermissions(this, permissions, 0)
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
                            AsyncTask.execute {
                                val builder = initSyncNotificationBuilder(this).apply {
                                    setSmallIcon(R.drawable.ic_notification)
                                    setContentTitle(getString(R.string.notification_adding_events, info.name))
                                }
                                with(NotificationManagerCompat.from(this)) {
                                    notify(info.notificationId, builder.build())
                                }
                                AndroidCalendar.insertColors(provider, account) // Allow custom event colors
                                val decsync = getDecsync(info)
                                val extra = Extra(info, this, provider)
                                setNumProcessedEntries(extra, 0)
                                decsync.initStoredEntries()
                                decsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
                                with(NotificationManagerCompat.from(this)) {
                                    cancel(info.notificationId)
                                }
                            }
                        } else {
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

    private val onActionOverflowListener = { anchor: View, info: CollectionInfo ->
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.inflate(R.menu.account_collection_operations)

        popup.setOnMenuItemClickListener { item ->
            when (info.type) {
                CollectionInfo.Type.ADDRESS_BOOK -> {
                    var permissions = emptyArray<String>()
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        permissions += Manifest.permission.READ_CONTACTS
                    }
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        permissions += Manifest.permission.WRITE_CONTACTS
                    }
                    if (permissions.isNotEmpty()) {
                        ActivityCompat.requestPermissions(this, permissions, 0)
                        return@setOnMenuItemClickListener true
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
                        ActivityCompat.requestPermissions(this, permissions, 0)
                        return@setOnMenuItemClickListener true
                    }
                }
            }
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
                    var androidEntries: Int? = null
                    var processedEntries: Int? = null
                    info.getProviderClient(this)?.let { provider ->
                        try {
                            when (info.type) {
                                CollectionInfo.Type.ADDRESS_BOOK -> {
                                    processedEntries = AccountManager.get(this).getUserData(info.getAccount(this), KEY_NUM_PROCESSED_ENTRIES)?.toInt()
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
                                            processedEntries = if (calCursor.isNull(1)) null else calCursor.getInt(1)
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

                        class DecsyncEntriesTask(
                                val mContext: Context,
                                val mDialog: AlertDialog,
                                val mAndroidEntries: Int?,
                                val mProcessedEntries: Int?,
                                val mInfo: CollectionInfo
                        ) : AsyncTask<Void, Void, Int>() {
                            override fun onPreExecute() {
                                super.onPreExecute()
                                val message = mContext.getString(R.string.entries_count_message, mAndroidEntries, mProcessedEntries, "…")
                                mDialog.setMessage(message)
                                mDialog.show()
                            }

                            override fun doInBackground(vararg params: Void): Int {
                                class Extra(var count: Int)
                                val latestAppId = getDecsync(mInfo).latestAppId()
                                val countDecsync = Decsync<Extra>(mInfo.decsyncDir, mInfo.syncType, mInfo.collection, latestAppId)
                                countDecsync.addListener(emptyList()) { _, entry, extra ->
                                    if (!entry.value.isNull) {
                                        extra.count++
                                    }
                                }
                                val extra = Extra(0)
                                countDecsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
                                return extra.count
                            }

                            override fun onPostExecute(decsyncEntries: Int) {
                                super.onPostExecute(decsyncEntries)
                                val message = mContext.getString(R.string.entries_count_message, mAndroidEntries, mProcessedEntries, "%d".format(decsyncEntries))
                                mDialog.setMessage(message)
                            }
                        }

                        val dialog = AlertDialog.Builder(this)
                                .setTitle(R.string.entries_count_title)
                                .setNeutralButton(android.R.string.ok) { dialog, _ ->
                                    dialog.dismiss()
                                }
                                .create()
                        DecsyncEntriesTask(this, dialog, androidEntries, processedEntries, info).execute()
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
        info.getProviderClient(this)?.let { provider ->
            try {
                val extra = Extra(info, this, provider)
                when (info.type) {
                    CollectionInfo.Type.ADDRESS_BOOK -> {
                        ContactDecsyncUtils.infoListener(emptyList(), Decsync.Entry(key, value), extra)
                        getDecsync(info).setEntry(listOf("info"), key, value)
                        loadBooks()
                    }
                    CollectionInfo.Type.CALENDAR -> {
                        CalendarDecsyncUtils.infoListener(emptyList(), Decsync.Entry(key, value), extra)
                        getDecsync(info).setEntry(listOf("info"), key, value)
                        loadCalendars()
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


    /* LIST ADAPTERS */

    class AddressBookAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_address_book_item) {
        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_address_book_item, parent, false)
            val info = getItem(position)!!
            val isChecked = info.isEnabled(context)

            val checked: CheckBox = v.findViewById(R.id.checked)
            checked.isChecked = isChecked

            val tv: TextView = v.findViewById(R.id.title)
            tv.text = info.name

            v.findViewById<ImageView>(R.id.action_overflow).setOnClickListener { view ->
                (context as? MainActivity)?.let {
                    it.onActionOverflowListener(view, info)
                }
            }

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
}
