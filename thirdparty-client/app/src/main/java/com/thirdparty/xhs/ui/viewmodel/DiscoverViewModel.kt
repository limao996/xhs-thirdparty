package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.Category
import com.thirdparty.xhs.data.FollowedEntity
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Discover sub-tabs at the top of the 发现 screen. */
enum class DiscoverTab(val label: String) {
    FEED("发现"),
    FAN_GROUP("粉丝圈"),
    FOLLOW_LOCAL("关注")
}

/** Waterfall feed for a single category (v2/home/discover-note with category_id). */
data class FeedSection(
    val items: List<NoteItem> = emptyList(),
    val firstLoading: Boolean = false,
    val hasMore: Boolean = true
)

data class DiscoverUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategory: Int = 0,
    val feed: FeedSection = FeedSection(),
    val fanGroup: List<AuthorInfo> = emptyList(),
    val fanGroupLoading: Boolean = false,
    val followed: List<FollowedEntity> = emptyList()
)

class DiscoverViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(DiscoverUiState())
    val ui: StateFlow<DiscoverUiState> = _ui.asStateFlow()

    private var feedPage = 0
    private var feedLoading = false
    private var myId = 0

    init {
        viewModelScope.launch {
            val cats = runCatching { repo.categories() }.getOrDefault(emptyList())
            _ui.update { it.copy(categories = cats) }
            myId = runCatching { repo.myUserId() }.getOrDefault(0)
            loadFanGroup()
        }
        loadMore()
        refreshFollowed()
    }

    fun selectCategory(id: Int) {
        if (_ui.value.selectedCategory == id) return
        _ui.update { it.copy(selectedCategory = id, feed = FeedSection()) }
        feedPage = 0
        feedLoading = false
        loadMore()
    }

    fun loadMore(force: Boolean = false) {
        if (feedLoading || (!force && !_ui.value.feed.hasMore)) return
        feedLoading = true
        _ui.update { it.copy(feed = it.feed.copy(firstLoading = it.feed.items.isEmpty())) }
        val catId = _ui.value.selectedCategory
        viewModelScope.launch {
            try {
                val list = repo.discoverPage(categoryId = catId, groupId = 0, page = feedPage + 1)
                feedPage++
                _ui.update { s ->
                    s.copy(feed = s.feed.copy(
                        items = s.feed.items + list,
                        firstLoading = false,
                        hasMore = list.size >= 10
                    ))
                }
            } catch (e: Exception) {
                _ui.update { it.copy(feed = it.feed.copy(firstLoading = false)) }
            } finally {
                feedLoading = false
            }
        }
    }

    /** Full refresh: reload the feed (for the FAB), category list, fan-group recs & followed. */
    fun refresh(onDone: (() -> Unit)? = null) {
        feedPage = 0
        feedLoading = false
        _ui.update { it.copy(feed = FeedSection()) }
        viewModelScope.launch {
            val cats = runCatching { repo.categories() }.getOrDefault(emptyList())
            val catId = _ui.value.selectedCategory
            val list = runCatching { repo.discoverPage(categoryId = catId, groupId = 0, page = 1) }.getOrDefault(emptyList())
            feedPage = 1
            _ui.update {
                it.copy(categories = cats, feed = FeedSection(items = list, firstLoading = false, hasMore = list.size >= 10))
            }
            myId = runCatching { repo.myUserId() }.getOrDefault(myId)
            loadFanGroup()
            refreshFollowed()
            onDone?.invoke()
        }
    }

    private fun loadFanGroup() {
        _ui.update { it.copy(fanGroupLoading = true) }
        viewModelScope.launch {
            val recs = if (myId > 0) runCatching { repo.funGroupRecommend(myId) }.getOrDefault(emptyList())
                else emptyList()
            _ui.update { it.copy(fanGroup = recs, fanGroupLoading = false) }
        }
    }

    private fun refreshFollowed() {
        viewModelScope.launch {
            val followed = runCatching { repo.followedAuthors() }.getOrDefault(emptyList())
            _ui.update { it.copy(followed = followed) }
        }
    }

    /** Auto-refresh a specific sub-tab when the user switches to it. */
    fun refreshTab(tab: DiscoverTab) {
        when (tab) {
            DiscoverTab.FEED -> {
                feedPage = 0
                feedLoading = false
                _ui.update { it.copy(feed = FeedSection(firstLoading = true)) }
                loadMore(force = true)
            }
            DiscoverTab.FAN_GROUP -> {
                if (myId <= 0) viewModelScope.launch { myId = runCatching { repo.myUserId() }.getOrDefault(0); loadFanGroup() }
                else loadFanGroup()
            }
            DiscoverTab.FOLLOW_LOCAL -> refreshFollowed()
        }
    }
}