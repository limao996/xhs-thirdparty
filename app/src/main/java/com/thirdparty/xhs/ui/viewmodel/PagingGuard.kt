package com.thirdparty.xhs.ui.viewmodel

/**
 * Shared pagination bookkeeping.
 *
 * The backend's page boundaries are not stable: pages regularly come back short
 * (or even echo ids from the previous page). Treating "fewer than 10 items" as
 * the end therefore stopped pagination while more content existed — which is
 * exactly what "加载更多异常" looked like to the user. A short page is not the
 * end. Only an empty page, or two consecutive pages that added nothing new, is.
 */
class PagingGuard {
    private var emptyPages = 0

    /** Call once per loaded page; returns whether [loadMore] should stay enabled. */
    fun onPage(pageSize: Int, added: Int): Boolean {
        emptyPages = if (added == 0) emptyPages + 1 else 0
        return pageSize > 0 && emptyPages < 2
    }

    /** Call when the list is reset (refresh / category change / new search). */
    fun reset() {
        emptyPages = 0
    }
}
