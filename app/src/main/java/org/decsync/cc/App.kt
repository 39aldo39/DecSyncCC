package org.decsync.cc

import android.app.Application
import org.decsync.cc.model.AppDatabase

class App : Application() {
    companion object {
        lateinit var db: AppDatabase
        private set
    }

    override fun onCreate() {
        super.onCreate()

        db = AppDatabase.createDatabase(applicationContext)
    }
}