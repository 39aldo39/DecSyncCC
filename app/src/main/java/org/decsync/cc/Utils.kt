package org.decsync.cc

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import at.bitfire.ical4android.AndroidTaskList
import at.bitfire.ical4android.TaskProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.cc.tasks.LocalTaskList

object Utils {
    private const val TAG = "Utils"

    val TASK_PROVIDERS = listOf(
            TaskProvider.ProviderName.OpenTasks,
            TaskProvider.ProviderName.TasksOrg
    )
    val TASK_PROVIDER_NAMES = listOf(
            R.string.tasks_app_opentasks,
            R.string.tasks_app_tasks_org
    )

    fun appInstalled(activity: Activity, packageName: String): Boolean {
        return try {
            activity.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun installApp(activity: Activity, packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("market://details?id=$packageName")
        if (intent.resolveActivity(activity.packageManager) == null) {
            Toast.makeText(activity, R.string.no_app_store, Toast.LENGTH_SHORT).show()
            return
        }
        activity.startActivity(intent)
    }

    fun parseColor(decsyncColor: String): Int? {
        return try {
            Color.parseColor(decsyncColor)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown color $decsyncColor", e)
            null
        }
    }

    fun colorToString(color: Int): String {
        return String.format("#%06X", color and 0xFFFFFF)
    }

    @ExperimentalStdlibApi
    suspend fun checkCollectionEnabled(context: Context, decsyncDir: DecsyncDirectory): Boolean {
        return if (anyAddressBookEnabled(context, decsyncDir) ||
            anyCalendarEnabledForDir(context, decsyncDir) ||
            anyTaskListEnabledForDir(context, decsyncDir)) {
            val title = context.getString(R.string.settings_decsync_collections_enabled_title)
            val message = context.getString(R.string.settings_decsync_collections_enabled_message)
            showBasicDialog(context, title, message)
            true
        } else {
            false
        }
    }

    @ExperimentalStdlibApi
    suspend fun checkTaskListsEnabled(context: Context): Boolean {
        return if (anyTaskListEnabledForAnyDir(context)) {
            val title = context.getString(R.string.settings_decsync_task_lists_enabled_title)
            val message = context.getString(R.string.settings_decsync_task_lists_enabled_message)
            showBasicDialog(context, title, message)
            true
        } else {
            false
        }
    }

    private fun anyAddressBookEnabled(context: Context, decsyncDir: DecsyncDirectory): Boolean {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(context.getString(R.string.account_type_contacts))
        return accounts.any { account ->
            accountManager.getUserData(account, AddressBookInfo.KEY_DECSYNC_DIR_ID).toLong() == decsyncDir.id
        }
    }

    private suspend fun anyCalendarEnabledForAnyDir(context: Context): Boolean {
        val decsyncDirs = App.db.decsyncDirectoryDao().all()
        return anyCalendarEnabledForDirs(context, decsyncDirs)
    }

    private fun anyCalendarEnabledForDir(context: Context, decsyncDir: DecsyncDirectory): Boolean {
        return anyCalendarEnabledForDirs(context, listOf(decsyncDir))
    }

    private fun anyCalendarEnabledForDirs(context: Context, decsyncDirs: List<DecsyncDirectory>): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val provider = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return false
        try {
            for (decsyncDir in decsyncDirs) {
                val account = decsyncDir.getCalendarAccount(context)
                provider.query(
                    syncAdapterUri(account, CalendarContract.Calendars.CONTENT_URI), emptyArray(),
                    null, null, null)!!.use { cursor ->
                    if (cursor.moveToFirst()) return true
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }
        return false
    }

    @ExperimentalStdlibApi
    private suspend fun anyTaskListEnabledForAnyDir(context: Context): Boolean {
        val decsyncDirs = App.db.decsyncDirectoryDao().all()
        return anyTaskListEnabledForDirs(context, decsyncDirs)
    }

    @ExperimentalStdlibApi
    private fun anyTaskListEnabledForDir(context: Context, decsyncDir: DecsyncDirectory): Boolean {
        return anyTaskListEnabledForDirs(context, listOf(decsyncDir))
    }

    @ExperimentalStdlibApi
    private fun anyTaskListEnabledForDirs(context: Context, decsyncDirs: List<DecsyncDirectory>): Boolean {
        val authority = PrefUtils.getTasksAuthority(context) ?: return false
        val providerName = TaskProvider.ProviderName.fromAuthority(authority)
        for (permission in providerName.permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        val provider = context.contentResolver.acquireContentProviderClient(authority) ?: return false
        try {
            val taskProvider = TaskProvider.fromProviderClient(context, providerName, provider)
            for (decsyncDir in decsyncDirs) {
                val account = decsyncDir.getTaskListAccount(context)
                val taskLists = AndroidTaskList.find(account, taskProvider, LocalTaskList.Factory, null, null)
                if (taskLists.isNotEmpty()) return true
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }
        return false
    }

    fun showBasicDialog(context: Context, title: String?, message: String?) {
        CoroutineScope(Dispatchers.Main).launch {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        }
    }
}