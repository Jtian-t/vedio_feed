package com.example.feedvideo.data.repository

import com.example.feedvideo.data.model.Video
import com.example.feedvideo.data.source.MockDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 视频数据 Repository，封装数据获取逻辑。
 * 管理视频列表状态、分页加载、刷新等。
 */
class VideoRepository {

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private var currentPage = 0
    private var hasMore = true
    private var isLoading = false

    /**
     * 加载首页数据
     */
    suspend fun loadFirstPage(): Result<List<Video>> {
        if (isLoading) return Result.success(_videos.value)
        isLoading = true
        return try {
            val (newVideos, more) = MockDataSource.loadVideos(0)
            currentPage = 0
            hasMore = more
            _videos.value = newVideos
            Result.success(newVideos)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isLoading = false
        }
    }

    /**
     * 加载下一页
     */
    suspend fun loadNextPage(): Result<List<Video>> {
        if (isLoading || !hasMore) return Result.success(_videos.value)
        isLoading = true
        return try {
            val nextPage = currentPage + 1
            val (newVideos, more) = MockDataSource.loadVideos(nextPage)
            currentPage = nextPage
            hasMore = more
            val currentList = _videos.value
            _videos.value = currentList + newVideos
            Result.success(_videos.value)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isLoading = false
        }
    }

    /**
     * 刷新数据
     */
    suspend fun refresh(): Result<List<Video>> {
        if (isLoading) return Result.success(_videos.value)
        isLoading = true
        return try {
            val (newVideos, more) = MockDataSource.refresh()
            currentPage = 0
            hasMore = more
            _videos.value = newVideos
            Result.success(newVideos)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isLoading = false
        }
    }

    fun hasMore(): Boolean = hasMore

    /**
     * 更新视频点赞状态
     */
    fun toggleLike(videoId: String) {
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == videoId }
        if (index != -1) {
            val video = currentList[index]
            currentList[index] = video.copy(
                isLiked = !video.isLiked,
                likeCount = if (video.isLiked) video.likeCount - 1 else video.likeCount + 1
            )
            _videos.value = currentList
        }
    }

    /**
     * 添加评论
     */
    fun addComment(videoId: String) {
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == videoId }
        if (index != -1) {
            val video = currentList[index]
            currentList[index] = video.copy(commentCount = video.commentCount + 1)
            _videos.value = currentList
        }
    }
}
