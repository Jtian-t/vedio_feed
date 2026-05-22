package com.example.feedvideo.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.feedvideo.data.model.Video
import com.example.feedvideo.data.repository.VideoRepository
import com.example.feedvideo.player.PreloadManager
import com.example.feedvideo.player.VideoPlayer
import com.example.feedvideo.FeedVideoApp
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Feed 列表 ViewModel — 管理视频列表状态、播放控制、预加载。
 */
class FeedViewModel(application: Application) : AndroidViewModel(application) {

    val repository = VideoRepository()
    val player = VideoPlayer(application)
    val preloadManager = PreloadManager()
    
    // 使用全局单例代理
    val videoProxy = (application as FeedVideoApp).videoProxy

    val videos: StateFlow<List<Video>> = repository.videos

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // 评论 UI 状态
    private val _showComments = MutableStateFlow(false)
    val showComments: StateFlow<Boolean> = _showComments.asStateFlow()

    // 模拟评论列表
    private val _comments = MutableStateFlow<List<String>>(emptyList())
    val comments: StateFlow<List<String>> = _comments.asStateFlow()

    init {
        loadFirstPage()
    }

    /**
     * 加载首页
     */
    fun loadFirstPage() {
        viewModelScope.launch {
            repository.loadFirstPage()
            _hasMore.value = repository.hasMore()
            // 首个视频自动播放
            if (videos.value.isNotEmpty()) {
                updatePreload(0)
            }
        }
    }

    /**
     * 下拉刷新
     */
    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            repository.refresh()
            _hasMore.value = repository.hasMore()
            _currentIndex.value = 0
            updatePreload(0)
            _isRefreshing.value = false
        }
    }

    /**
     * 上拉加载更多
     */
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            repository.loadNextPage()
            _hasMore.value = repository.hasMore()
            _isLoadingMore.value = false
        }
    }

    /**
     * 切换到指定视频
     */
    fun switchToVideo(index: Int) {
        if (index == _currentIndex.value) return
        _currentIndex.value = index
        updatePreload(index)
    }

    /**
     * 更新预加载窗口
     */
    private fun updatePreload(index: Int) {
        preloadManager.updatePreloadWindow(videos.value, index)
    }

    /**
     * 切换点赞状态
     */
    fun toggleLike(videoId: String) {
        repository.toggleLike(videoId)
    }

    /**
     * 显示/隐藏评论
     */
    fun toggleComments() {
        _showComments.value = !_showComments.value
    }

    /**
     * 添加评论
     */
    fun addComment(videoId: String, content: String) {
        if (content.isBlank()) return
        repository.addComment(videoId)
        _comments.value = _comments.value + content
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
        preloadManager.release()
    }
}
