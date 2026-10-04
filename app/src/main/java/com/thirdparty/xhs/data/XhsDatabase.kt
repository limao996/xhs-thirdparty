package com.thirdparty.xhs.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SavedNoteEntity::class, HistoryEntity::class, FollowedEntity::class],
    version = 2,
    exportSchema = false
)
abstract class XhsDatabase : RoomDatabase() {
    abstract fun savedDao(): SavedNoteDao
    abstract fun historyDao(): HistoryDao
    abstract fun followDao(): FollowDao

    companion object {
        @Volatile
        private var INSTANCE: XhsDatabase? = null

        fun get(context: Context): XhsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    XhsDatabase::class.java,
                    "xhs_local.db"
                )
                    // personal third-party client: schema change wipes local cache (favorites/history re-fetchable)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}