package com.thirdparty.xhs.data

import org.json.JSONObject

/**
 * Result of probing one candidate guest identity (see XhsApi.probeAccount).
 * Only identities the backend already has an account for produce a value.
 */
data class AccountProbe(
    val mac: String,
    val userId: Int,
    val userName: String,
    /** `user_vp.vp_status` — 1 means the account currently carries VIP */
    val vipStatus: Int,
    /** `user_vp.vp_end`, seconds; 0 when the account never had VIP */
    val vipEnd: Long
) {
    val isVip: Boolean get() = vipStatus >= 1 || vipEnd > System.currentTimeMillis() / 1000
}

/**
 * A recommended fan-group author (v2/member/fun-group-list → recommend_list).
 *
 * The payload also carries a `note_list` preview (up to 3 works) and the
 * author's total `user_notes`; both used to be discarded, leaving the 粉丝圈 tab
 * as a bare list of names even though the backend supplies real content.
 */
data class FanGroupAuthor(
    val userId: Int,
    val userName: String,
    val headImg: String,
    /** total works published by this author (`user_notes`) */
    val noteCount: Int,
    /** preview works, ready to render as cards */
    val notes: List<NoteItem> = emptyList()
)

/** Guest's own profile (v2/mine/user-info) — used by the "我的" screen. */
data class UserProfile(
    val userId: Int,
    val userName: String,
    val headImg: String,
    val backgroundImg: String,
    val signature: String,
    val level: String,
    val follows: Int,
    val fans: Int,
    val notes: Int,
    val vipStatus: Int,   // 0 none, 1 vip
    val vipEnd: Long,
    val svipEnd: Long,
    val phoneBound: Boolean
) {
    val isVip: Boolean get() = vipStatus >= 1 || vipEnd > System.currentTimeMillis() / 1000

    companion object {
        fun from(o: JSONObject): UserProfile? {
            val ui = o.optJSONObject("user_info") ?: return null
            val vp = o.optJSONObject("user_vp") ?: JSONObject()
            return UserProfile(
                userId = ui.optInt("user_id"),
                userName = ui.optString("user_name", "游客"),
                headImg = ui.optString("user_head_img"),
                backgroundImg = ui.optString("user_background_img"),
                signature = ui.optString("user_signature"),
                level = ui.optString("user_level_name"),
                follows = ui.optInt("user_follows"),
                fans = ui.optInt("user_funs"),
                notes = ui.optInt("user_notes"),
                vipStatus = vp.optInt("vp_status"),
                vipEnd = vp.optLong("vp_end"),
                svipEnd = vp.optLong("svp_end"),
                phoneBound = ui.optString("user_phone").isNotEmpty()
            )
        }
    }
}

/** An author's public profile (v2/member/user-info / v2/member/note-list data). */
data class AuthorInfo(
    val userId: Int,
    val userName: String,
    val headImg: String,
    val signature: String,
    val vpStatus: Int,
    val isFollow: Boolean
) {
    companion object {
        fun from(o: JSONObject): AuthorInfo {
            // NOTE: member/user-info nests these under "user_info" (top-level
            // user_id is null), so read the nested object first.
            val ui = o.optJSONObject("user_info")
            val uid = ui?.optInt("user_id")?.takeIf { it > 0 } ?: o.optInt("user_id")
            return AuthorInfo(
                userId = uid,
                userName = ui?.optString("user_name").orEmpty().ifEmpty { o.optString("user_name") },
                headImg = ui?.optString("user_head_img").orEmpty().ifEmpty { o.optString("user_head_img") },
                signature = ui?.optString("user_signature").orEmpty().ifEmpty { o.optString("user_signature") },
                vpStatus = ui?.optInt("user_vp_status") ?: o.optInt("user_vp_status"),
                isFollow = o.optBoolean("is_follow") || o.optInt("is_follow") == 1 ||
                    ui?.optInt("is_follow") == 1
            )
        }
    }
}

/** A reply to a top-level comment (nested in `reply_data.list`). */
data class CommentReply(
    val replyId: Int,
    val userId: Int,
    val userName: String,
    val headImg: String,
    val content: String,
    val createdAt: Long,
    val likeCount: Int,
    /** whom this reply answers ("" when replying to the comment itself) */
    val replyToName: String
) {
    companion object {
        fun from(o: JSONObject): CommentReply = CommentReply(
            replyId = o.optInt("reply_id"),
            userId = o.optInt("user_id"),
            userName = o.optString("user_name", "匿名"),
            headImg = o.optString("user_head_img"),
            content = o.optString("content"),
            createdAt = o.optLong("created_at") * 1000L,
            likeCount = o.optInt("like_count"),
            replyToName = o.optString("parent_reply_user_name")
        )
    }
}

/** A top-level comment (v2/note-comment/comment-list). */
data class CommentItem(
    val commentId: Int,
    val userId: Int,
    val userName: String,
    val headImg: String,
    val content: String,
    val createdAt: Long,
    val likeCount: Int,
    val isLike: Boolean,
    val replyCount: Int,
    /** replies come inline (there is no separate reply endpoint) */
    val replies: List<CommentReply> = emptyList()
) {
    companion object {
        fun from(o: JSONObject): CommentItem {
            // replies live under reply_data; the top level has no data_count
            val rd = o.optJSONObject("reply_data")
            val totalReply = rd?.optInt("data_count") ?: 0
            val arr = rd?.optJSONArray("list")
            val replies = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(CommentReply.from(it)) }
                    }
                }
            }
            return CommentItem(
                commentId = o.optInt("comment_id"),
                userId = o.optInt("user_id"),
                userName = o.optString("user_name", "匿名"),
                headImg = o.optString("user_head_img"),
                content = o.optString("content"),
                createdAt = o.optLong("created_at") * 1000L,
                likeCount = o.optInt("like_count"),
                isLike = o.optInt("is_like") == 1,
                replyCount = totalReply,
                replies = replies
            )
        }
    }
}

/** A discover category (v2/home/discover-category). */
data class Category(val id: Int, val name: String) {
    companion object {
        fun from(o: JSONObject): Category = Category(o.optInt("id"), o.optString("name"))
    }
}