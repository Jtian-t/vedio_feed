package com.example.feedvideo.data.source

import com.example.feedvideo.data.model.Video
import kotlinx.coroutines.delay

/**
 * Mock 数据源，提供 100+ 视频 URL，支持分页加载。
 * 使用公共视频源（Google Storage 示例视频）。
 */
object MockDataSource {

    // 公共可用的 MP4 视频 URL 列表
    private val videoUrls = listOf(
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/SubaruOutbackOnStreetAndDirt.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/VolkswagenGTIReview.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4",
    )

    private val titles = listOf(
        "大兔子的冒险之旅",
        "大象之梦",
        "火焰挑战者",
        "极限逃脱",
        "欢乐时光",
        "极速狂飙",
        "末日熔炉",
        "辛特尔的传说",
        "街道与泥地",
        "钢铁之泪",
        "大众GTI评测",
        "公牛奔跑记",
        "一千块能买什么车",
    )

    private val authors = listOf(
        "Blender Foundation", "Blender Foundation", "Google",
        "Google", "Google", "Google", "Google",
        "Blender Foundation", "GoPro", "Blender Foundation",
        "MotorTrend", "Holden", "CarWow"
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
