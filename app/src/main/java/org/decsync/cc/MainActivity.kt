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
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.ContactsContract
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.*
import android.widget.*
import kotlinx.android.synthetic.main.activity_main.*
import org.decsync.cc.calendars.CalendarDecsyncUtils
import org.decsync.cc.calendars.CalendarDecsyncUtils.addColor
import org.decsync.cc.contacts.ContactDecsyncUtils
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.Decsync
import org.decsync.library.getDecsyncSubdir
import org.decsync.library.listDecsyncCollections
import java.util.Random

const val TAG = "DecSyncCC"

class MainActivity: AppCompatActivity(), Toolbar.OnMenuItemClickListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
    }

    override fun onResume() {
        super.onResume()

        loadBooks()
        loadCalendars()
    }

    private fun loadBooks() {
        val adapter = AddressBookAdapter(this)

        adapter.clear()
        adapter.addAll(
                listDecsyncCollections(null, "contacts").map {
                    val dir = getDecsyncSubdir(null, "contacts", it)
                    val name = Decsync.getStoredStaticValue(dir, listOf("info"), "name") as? String ?: it
                    CollectionInfo(CollectionInfo.Type.ADDRESS_BOOK, it, name)
                }
        )

        address_books.adapter = adapter
        address_books.onItemClickListener = onItemClickListener
    }

    private fun loadCalendars() {
        val adapter = CalendarAdapter(this)

        adapter.clear()
        adapter.addAll(
                listDecsyncCollections(null, "calendars").map {
                    val dir = getDecsyncSubdir(null, "calendars", it)
                    val name = Decsync.getStoredStaticValue(dir, listOf("info"), "name") as? String ?: it
                    CollectionInfo(CollectionInfo.Type.CALENDAR, it, name)
                }
        )

        calendars.adapter = adapter
        calendars.onItemClickListener = onItemClickListener
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
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
                        .setTitle("Name for new collection")
                        .setView(input)
                        .setPositiveButton("OK") { _, _ ->
                            val name = input.text.toString()
                            if (!name.isBlank()) {
                                val id = "colID%05d".format(Random().nextInt(100000))
                                val info = CollectionInfo(CollectionInfo.Type.ADDRESS_BOOK, id, name)
                                Decsync<Unit>(info.dir, ownAppId, emptyList()).setEntry(listOf("info"), "name", name)
                                loadBooks()
                            }
                        }
                        .setNegativeButton("Cancel") { _, _ -> }
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
                        .setTitle("Name for new collection")
                        .setView(input)
                        .setPositiveButton("OK") { _, _ ->
                            val name = input.text.toString()
                            if (!name.isBlank()) {
                                val id = "colID%05d".format(Random().nextInt(100000))
                                val info = CollectionInfo(CollectionInfo.Type.CALENDAR, id, name)
                                Decsync<Unit>(info.dir, ownAppId, emptyList()).setEntry(listOf("info"), "name", name)
                                loadCalendars()
                            }
                        }
                        .setNegativeButton("Cancel") { _, _ -> }
                        .show()
            }
        }
        return false
    }

    private val onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, _ ->
        if (!view.isEnabled)
            return@OnItemClickListener

        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<CollectionInfo>
        val info = adapter.getItem(position)
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
                        val decsync = getDecsync(info)
                        decsync.initStoredEntries()
                        contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)?.let { provider ->
                            try {
                                decsync.executeStoredEntries(listOf("resources"), Extra(info, this, provider))
                            } finally {
                                if (Build.VERSION.SDK_INT >= 24)
                                    provider.close()
                                else
                                    @Suppress("DEPRECATION")
                                    provider.release()
                            }
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
                            Decsync.getStoredStaticValue(info.dir, listOf("info"), "color")?.let { color ->
                                addColor(values, color)
                            }
                            provider.insert(syncAdapterUri(account, Calendars.CONTENT_URI), values)
                            AsyncTask.execute {
                                val decsync = getDecsync(info)
                                decsync.initStoredEntries()
                                decsync.executeStoredEntries(listOf("resources"), Extra(info, this, provider))
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
        val popup = PopupMenu(this, anchor, Gravity.RIGHT)
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
                            .setTitle("New name for collection")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                val name = input.text.toString()
                                if (!name.isBlank() && name != info.name) {
                                    setCollectionInfo(info, "name", name)
                                }
                            }
                            .setNegativeButton("Cancel") { _, _ -> }
                            .show()
                }
                R.id.delete_collection -> {
                    AlertDialog.Builder(this)
                            .setTitle("Are you sure you want to delete the collection '${info.name}'?")
                            .setPositiveButton("OK") { _, _ ->
                                setCollectionInfo(info, "deleted", true)
                            }
                            .setNegativeButton("Cancel") { _, _ -> }
                            .show()
                }
            }
            true
        }
        popup.show()

        // long click was handled
        true
    }

    private fun setCollectionInfo(info: CollectionInfo, key: Any, value: Any) {
        Log.d(TAG, "Set info for ${info.id} of key $key to value $value")
        contentResolver.acquireContentProviderClient(
                when (info.type) {
                    CollectionInfo.Type.ADDRESS_BOOK -> ContactsContract.AUTHORITY
                    CollectionInfo.Type.CALENDAR -> CalendarContract.AUTHORITY
                }
        )?.let { provider ->
            try {
                val extra = Extra(info, this, provider)
                when (info.type) {
                    CollectionInfo.Type.ADDRESS_BOOK -> {
                        (ContactDecsyncUtils::InfoListener)().onSubfileEntryUpdate(Decsync.Entry(key, value), extra)
                        getDecsync(info).setEntry(listOf("info"), key, value)
                        loadBooks()
                    }
                    CollectionInfo.Type.CALENDAR -> {
                        (CalendarDecsyncUtils::InfoListener)().onSubfileEntryUpdate(Decsync.Entry(key, value), extra)
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
        override fun getView(position: Int, v: View?, parent: ViewGroup?): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_address_book_item, parent, false)
            val info = getItem(position)
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
        override fun getView(position: Int, v: View?, parent: ViewGroup?): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_calendar_item, parent, false)
            val info = getItem(position)
            val isChecked = info.isEnabled(context)

            val checked: CheckBox = v.findViewById(R.id.checked)
            checked.isChecked = isChecked

            val vColor: View = v.findViewById(R.id.color)
            vColor.visibility = (Decsync.getStoredStaticValue(info.dir, listOf("info"), "color") as? String)?.let {
                try { Color.parseColor(it) } catch (e: IllegalArgumentException) { null }
            }?.let {
                vColor.setBackgroundColor(it)
                View.VISIBLE
            } ?: View.INVISIBLE

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
}
