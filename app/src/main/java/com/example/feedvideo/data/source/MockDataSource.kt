package com.example.feedvideo.data.source

import com.example.feedvideo.data.model.Video
import kotlinx.coroutines.delay

/**
 * Mock 数据源，提供 100+ 视频 URL，支持分页加载。
 * 使用公开可访问的 MP4 视频源。
 */
object MockDataSource {

    // 公开可访问的 MP4 视频 URL 列表（已验证可用）
    private val videoUrls = listOf(
        "https://media.w3.org/2010/05/sintel/trailer.mp4",       // Sintel 预告片
        "https://media.w3.org/2010/05/bunny/trailer.mp4",         // Bunny 预告片
        "https://media.w3.org/2010/05/video/movie_300.mp4",       // W3C 测试视频
        "https://vjs.zencdn.net/v/oceans.mp4",                    // Oceans
        "https://www.w3schools.com/html/mov_bbb.mp4",             // Big Buck Bunny 短片
        "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4",
    )

    private val titles = listOf(
        "辛特尔的传说",
        "兔子的春天",
        "W3C 测试视频",
        "海洋世界",
        "大兔子短片",
        "Blender 动画",
    )

    private val authors = listOf(
        "Blender Foundation", "Blender Foundation", "W3C",
        "VideoJS", "W3Schools", "Blender"
    )

    // 生成 120 条 mock 数据（循环使用 URL）
    private val allVideos: List<Video> by lazy {
        (0 until 120).map { index ->
            val urlIndex = index % videoUrls.size
            Video(
                id = "video_${index + 1}",
                url = videoUrls[urlIndex],
                title = "${titles[urlIndex]} #${index + 1}",
                author = authors[urlIndex],
                likeCount = (100..99999).random(),
                commentCount = (10..9999).random()
            )
        }
    }

    private const val PAGE_SIZE = 15

    /**
     * 分页加载视频列表
     * @param page 页码（从 0 开始）
     * @return Pair<videos, hasMore>
     */
    suspend fun loadVideos(page: Int = 0): Pair<List<Video>, Boolean> {
        // 模拟网络延迟
        delay(300L)

        val startIndex = page * PAGE_SIZE
        if (startIndex >= allVideos.size) {
            return emptyList<Video>() to false
        }

        val endIndex = minOf(startIndex + PAGE_SIZE, allVideos.size)
        val videos = allVideos.subList(startIndex, endIndex)
        val hasMore = endIndex < allVideos.size

        return videos to hasMore
    }

    /**
     * 刷新获取最新数据（模拟）
     */
    suspend fun refresh(): Pair<List<Video>, Boolean> {
        delay(500L)
        // 返回第一批数据，模拟刷新
        return allVideos.take(PAGE_SIZE) to true
    }
}
