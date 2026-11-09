package com.thirdparty.xhs.data

import android.content.Context
import androidx.core.content.edit

/**
 * 视频观看进度（按作品 id）—— 2026-11-09 完全重写。
 *
 * 只用 SharedPreferences：这个数据量很小（上限 [MAX_ENTRIES] 条，按时间淘汰），
 * 放进 Room 要多一次版本迁移，收益不成正比。
 *
 * 值格式：`位置毫秒|时长毫秒|记录时间戳`，坏值一律当"没有记录"，不抛异常。
 */
object VideoProgressStore {

    private const val PREFS = "video_progress"
    private const val SEPARATOR = "|"
    private const val MAX_ENTRIES = 300

    /** 一条记录。 */
    data class Progress(val positionMs: Long, val durationMs: Long, val savedAtMs: Long)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取；没有记录或值损坏时返回 null。 */
    fun load(context: Context, noteId: Long): Progress? {
        val raw = prefs(context).getString(noteId.toString(), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != 3) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        if (pos <= 0L) return null
        return Progress(pos, dur, at)
    }

    /** 记录。位置 <= 0 时忽略（预载/未开播的播放器不该写）。 */
    fun save(context: Context, noteId: Long, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L) return
        val p = prefs(context)
        p.edit {
            putString(
                noteId.toString(),
                positionMs.toString() + SEPARATOR + durationMs + SEPARATOR + System.currentTimeMillis()
            )
        }
        pruneIfNeeded(p)
    }

    /** 删除某条（看完时用）。 */
    fun clear(context: Context, noteId: Long) {
        prefs(context).edit { remove(noteId.toString()) }
    }

    private fun pruneIfNeeded(p: android.content.SharedPreferences) {
        val all = p.all
        if (all.size <= MAX_ENTRIES) return
        // 按记录时间淘汰最旧的一批
        val stale = all.entries
            .mapNotNull { (key, value) ->
                val at = (value as? String)?.split(SEPARATOR)?.getOrNull(2)?.toLongOrNull()
                if (at == null) key to 0L else key to at
            }
            .sortedBy { it.second }
            .take(all.size - MAX_ENTRIES)
        if (stale.isEmpty()) return
        p.edit { stale.forEach { (key, _) -> remove(key) } }
    }
}

/**
 * 「要不要提示继续观看」的纯规则（带单测）。
 *
 * - 看到不足 [MIN_POSITION_MS]：太短，没意义；
 * - 剩余不足 [END_GUARD_MS]：当已看完；
 * - 时长未知（<= 0）：只要位置够长就提示。
 */
object ResumeRule {
    /** 太短没有提示意义（不足 2 秒）。 */
    const val MIN_POSITION_MS = 2_000L

    /** 停在最后一秒以内视为看完（只排除"已经播完"这一种情况）。 */
    const val END_GUARD_MS = 1_000L

    /**
     * 只要"看过一段"就提示 —— 用户 2026-11-09 明确要求：
     * 「只要进入详情页就开始检查」，所以不再用"剩余不足 N 秒算看完"这类过滤，
     * 短片段（十几秒）中途退出也会提示；唯一排除的是已经播到结尾。
     */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_POSITION_MS) return false
        if (durationMs <= 0L) return true
        return positionMs < durationMs - END_GUARD_MS
    }
}
