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
         * v1 → v2：重建三张本地缓存表。
         *
         * 为什么要有这一条：v1 时代改 schema 用的是 `fallbackToDestructiveMigration()`，
         * 所以**从来没写过 1→2 的迁移**；而现在这条兜底被刻意去掉了（见下面 `get` 的注释），
         * 停在 v1 的库一打开就会抛 `IllegalStateException` —— 启动即崩。开发机/极旧的安装
         * 上确实可能有 v1。
         *
         * 代价说清楚：这里**清掉**收藏 / 最近浏览 / 关注（v1 的列结构已不可考），
         * 这三张表都只是服务端数据的本地副本，可以重新拉/重新收藏；相比"启动崩溃"这是
         * 有意的取舍（v1 从未对外发布 —— `docs/BUILD.md` 的发布记录从 1.2.1 起）。
         * 注意 `saved_notes` 的名称是历史遗留，表名必须与 `@Entity(tableName = …)` 完全一致。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `saved_notes`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_notes` (" +
                        "`noteId` INTEGER NOT NULL PRIMARY KEY, " +
                        "`title` TEXT NOT NULL, " +
                        "`userName` TEXT NOT NULL, " +
                        "`cover` TEXT NOT NULL, " +
                        "`noteType` INTEGER NOT NULL, " +
                        "`rawJson` TEXT NOT NULL, " +
                        "`savedAt` INTEGER NOT NULL)"
                )
                db.execSQL("DROP TABLE IF EXISTS `history`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history` (" +
                        "`noteId` INTEGER NOT NULL PRIMARY KEY, " +
                        "`title` TEXT NOT NULL, " +
                        "`userName` TEXT NOT NULL, " +
                        "`cover` TEXT NOT NULL, " +
                        "`noteType` INTEGER NOT NULL, " +
                        "`rawJson` TEXT NOT NULL, " +
                        "`viewedAt` INTEGER NOT NULL)"
                )
                db.execSQL("DROP TABLE IF EXISTS `followed_authors`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `followed_authors` (" +
                        "`userId` INTEGER NOT NULL PRIMARY KEY, " +
                        "`userName` TEXT NOT NULL, " +
                        "`headImg` TEXT NOT NULL, " +
                        "`signature` TEXT NOT NULL, " +
                        "`followedAt` INTEGER NOT NULL)"
                )
            }
        }

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // **故意不挂 `fallbackToDestructiveMigration()`**：那会让"漏写迁移"的版本
                    // 静默清空用户的收藏 / 浏览 / 关注 / 队列。宁可启动就崩，也不能悄悄丢数据
                    // （见 docs/REVIEW.md P1-3 与附录C-P0-2；AGENTS 硬约束与 CONVENTIONS 都要求真迁移）。
                    .build().also { INSTANCE = it }
            }
    }
}