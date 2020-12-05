package org.decsync.cc

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import at.bitfire.ical4android.TaskProvider

object Utils {
    private const val TAG = "Utils"

    // tasks.org doesn't work yet
    val TASK_PROVIDERS = listOf(
            TaskProvider.ProviderName.OpenTasks
    )
    val TASK_PROVIDER_NAMES = listOf(
            R.string.tasks_app_opentasks
    )

    fun installApp(activity: Activity, packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("market://details?id=$packageName")
        if (intent.resolveActivity(activity.packageManager) == null) {
            intent.data = Uri.parse("https://f-droid.org/app/$packageName")
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
}