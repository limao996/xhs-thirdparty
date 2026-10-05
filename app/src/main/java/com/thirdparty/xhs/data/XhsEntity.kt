package com.thirdparty.xhs.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_notes")
data class SavedNoteEntity(
    @PrimaryKey val noteId: Long,
    val title: String,
    val userName: String,
    val cover: String,
    val noteType: Int,
    val rawJson: String,
    val savedAt: Long
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val noteId: Long,
    val title: String,
    val userName: String,
    val cover: String,
    val noteType: Int,
    val rawJson: String,
    val viewedAt: Long
)

/**
 * 稍后观看队列：完全本地，而且**有序**（[position] 从 0 开始，小的在前）。
 *
 * 与收藏的区别：收藏是「留着」的集合，不看顺序；队列是「接下来按这个顺序看」，
 * 所以能上下移动、看完就删。同一个作品可以既收藏又在队列里，两者互不影响。
 */
@Entity(tableName = "watch_later")
data class WatchLaterEntity(
    @PrimaryKey val noteId: Long,
    val title: String,
    val userName: String,
    val cover: String,
    val noteType: Int,
    val rawJson: String,
    /** 队列位置，0 是队首；增删后用重排压紧，不留空洞 */
    val position: Int,
    val addedAt: Long
)