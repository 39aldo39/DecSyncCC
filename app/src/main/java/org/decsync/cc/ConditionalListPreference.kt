package org.decsync.cc

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference

class ConditionalListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {
    var condition: () -> Boolean = { true }

    override fun onClick() {
        if (condition()) {
            super.onClick()
        }
    }
}