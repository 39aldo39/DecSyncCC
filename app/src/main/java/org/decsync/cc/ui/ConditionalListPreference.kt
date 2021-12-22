package org.decsync.cc.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ConditionalListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {
    var condition: suspend () -> Boolean = { true }

    override fun onClick() {
        GlobalScope.launch {
            if (condition()) {
                super.onClick()
            }
        }
    }
}