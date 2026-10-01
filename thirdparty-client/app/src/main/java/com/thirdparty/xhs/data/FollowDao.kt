package com.thirdparty.xhs.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 关注（本地）：纯本地记录已关注的作者，不依赖云端关注状态。 */
@Entity(tableName = "followed_authors")
data class FollowedEntity(
    @PrimaryKey val userId: Int,
    val userName: String,
    val headImg: String,
    val signature: String = "",
    val followedAt: Long = System.currentTimeMillis()
)

@Dao
interface FollowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: FollowedEntity)

    @Query("DELETE FROM followed_authors WHERE userId = :uid")
    suspend fun remove(uid: Int)

    @Query("SELECT * FROM followed_authors ORDER BY followedAt DESC")
    suspend fun all(): List<FollowedEntity>

    @Query("SELECT COUNT(*) FROM followed_authors WHERE userId = :uid")
    suspend fun exists(uid: Int): Int

    @Query("DELETE FROM followed_authors")
    suspend fun clearLocal()
}