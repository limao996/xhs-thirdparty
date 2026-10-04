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