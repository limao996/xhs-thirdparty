package com.thirdparty.xhs.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 视频播放进度（"看到哪了"）的本地存储（用户 2026-11-09 要求：详情页回访时提示「继续观看上次位置」）。
 *
 * 为什么用 SharedPreferences 而不是 Room 表：它只是一张"作品 → 毫秒"的表，没有关联查询，
 * 加一张 Room 表就要写迁移（v5 → v6）并承担迁移风险，不值得。key 就是 noteId，
 * 值存 `位置|时长|写入时间`，超过 [MAX_ENTRIES] 条时按写入时间淘汰最旧的。
 *
 * 什么时候值得记：看到 [ResumeRule.MIN_POSITION_MS] 之后、且离结束还有 [ResumeRule.END_GUARD_MS] 以上。
 * 太靠前（点了就退）和已看完（或快看完）都不该提示"继续观看" —— 那是噪音。
 */
object VideoProgressStore {

    private const val PREFS = "video_progress"
    private const val MAX_ENTRIES = 300
    private const val SEPARATOR = '|'

    /** 一条可恢复的进度。[positionMs] 是上次离开时的位置。 */
    data class Progress(val positionMs: Long, val durationMs: Long)

    /** 判断"这条进度值不值得提示继续观看"的规则（纯逻辑，带单测）。 */
    object ResumeRule {
        /** 看到这个位置之前退出的，不算"看过"。 */
        const val MIN_POSITION_MS = 5_000L

        /** 离结尾不足这个距离的，视为已看完，不再提示。 */
        const val END_GUARD_MS = 10_000L

        /**
         * 时长未知（0）时只能靠位置判断，不能判断"是否看完"。
         *
         * 边界：剩余时长**刚好等于** [END_GUARD_MS] 时仍算"没看完"（"不足"才排除），
         * 所以判据是 `position <= duration - guard`，不是 `<`。
         */
        fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs < MIN_POSITION_MS) return false
            if (durationMs <= 0L) return true
            return positionMs <= durationMs - END_GUARD_MS
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记下进度；不值得恢复时顺手清掉旧值（看完 = 下次从头开始）。 */
    fun save(context: Context, noteId: Long, positionMs: Long, durationMs: Long) {
        if (noteId <= 0L) return
        val p = prefs(context)
        if (!ResumeRule.shouldResume(positionMs, durationMs)) {
            p.edit { remove(noteId.toString()) }
            return
        }
        p.edit {
            putString(
                noteId.toString(),
                positionMs.toString() + SEPARATOR + durationMs + SEPARATOR + System.currentTimeMillis()
            )
        }
        pruneIfNeeded(p)
    }

    /** 读进度；没有或格式不对返回 null（坏数据直接忽略，不让它挡住播放）。 */
    fun load(context: Context, noteId: Long): Progress? {
        if (noteId <= 0L) return null
        val raw = prefs(context).getString(noteId.toString(), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size < 2) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        return if (ResumeRule.shouldResume(pos, dur)) Progress(pos, dur) else null
    }

    /** 手动清掉某条（例如用户看完后选了"从头播放"）。 */
    fun clear(context: Context, noteId: Long) {
        if (noteId <= 0L) return
        prefs(context).edit { remove(noteId.toString()) }
    }

    /** 条目超上限时，按写入时间淘汰最旧的一批（只保留 [MAX_ENTRIES] 条）。 */
    private fun pruneIfNeeded(p: SharedPreferences) {
        val all = p.all
        if (all.size <= MAX_ENTRIES) return
        val stale = all.entries
            .mapNotNull { (k, v) ->
                val ts = (v as? String)?.split(SEPARATOR)?.getOrNull(2)?.toLongOrNull()
                if (ts == null) null else k to ts
            }
            .sortedBy { it.second }
            .take(all.size - MAX_ENTRIES)
        if (stale.isEmpty()) return
        p.edit {
            stale.forEach { remove(it.first) }
        }
    }
}
