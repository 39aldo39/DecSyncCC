package org.decsync.cc.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DecsyncDirectory::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decsyncDirectoryDao(): DecsyncDirectoryDao

    companion object {
        private const val DATABASE_NAME = "db"

        fun createDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME).build()
        }
    }
}