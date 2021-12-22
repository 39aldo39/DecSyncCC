package org.decsync.cc.model

import androidx.room.*

@Dao
abstract class DecsyncDirectoryDao {
    @Query("SELECT * FROM decsync_directories")
    abstract suspend fun all(): List<DecsyncDirectory>

    @Query("SELECT * FROM decsync_directories where id = :id")
    abstract suspend fun find(id: Long): DecsyncDirectory?

    @Query("SELECT * FROM decsync_directories where name = :name")
    abstract suspend fun findByName(name: String): DecsyncDirectory?

    @Query("SELECT * FROM decsync_directories where directory = :dir")
    abstract suspend fun findByDir(dir: String): DecsyncDirectory?

    @Query("SELECT * FROM decsync_directories where calendarAccountName = :calendarAccountName")
    abstract suspend fun findByCalendarAccountName(calendarAccountName: String): DecsyncDirectory?

    @Query("SELECT * FROM decsync_directories where taskListAccountName = :taskListAccountName")
    abstract suspend fun findByTaskListAccountName(taskListAccountName: String): DecsyncDirectory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(decsyncDirectory: DecsyncDirectory): Long

    @Update
    abstract suspend fun update(decsyncDirectories: List<DecsyncDirectory>)

    @Delete
    abstract suspend fun delete(decsyncDir: DecsyncDirectory)
}