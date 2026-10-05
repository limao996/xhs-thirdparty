package com.thirdparty.xhs.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SavedNoteEntity::class,
        HistoryEntity::class,
        FollowedEntity::class,
        WatchLaterEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class XhsDatabase : RoomDatabase() {
    abstract fun savedDao(): SavedNoteDao
    abstract fun historyDao(): HistoryDao
    abstract fun followDao(): FollowDao
    abstract fun watchLaterDao(): WatchLaterDao

    companion object {
        @Volatile
        private var INSTANCE: XhsDatabase? = null

        /**
         * v2 → v3：只是新增 `watch_later`（稍后观看队列）。
         *
         * 这一步必须是**真迁移**，不能靠 `fallbackToDestructiveMigration()` 兜底：
         * v1.2.1 已经发布，用户手机上的收藏 / 最近浏览 / 关注只有本机这一份，升级时清库
         * 等于把用户数据删掉，而这次改动只是加一张表、不动既有表。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_later` (" +
                        "`noteId` INTEGER NOT NULL PRIMARY KEY, " +
                        "`title` TEXT NOT NULL, " +
                        "`userName` TEXT NOT NULL, " +
                        "`cover` TEXT NOT NULL, " +
                        "`noteType` INTEGER NOT NULL, " +
                        "`rawJson` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): XhsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    XhsDatabase::class.java,
                    "xhs_local.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // **故意不挂 `fallbackToDestructiveMigration()`**：那会让"漏写迁移"的版本
                    // 静默清空用户的收藏 / 浏览 / 关注 / 队列。宁可启动就崩，也不能悄悄丢数据
                    // （见 docs/REVIEW.md P1-3 与附录C-P0-2；AGENTS 硬约束与 CONVENTIONS 都要求真迁移）。
                    .build().also { INSTANCE = it }
            }
    }
}