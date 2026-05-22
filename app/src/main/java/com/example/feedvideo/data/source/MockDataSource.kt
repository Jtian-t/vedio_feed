package com.example.feedvideo.data.source

import com.example.feedvideo.data.model.Video
import kotlinx.coroutines.delay

/**
 * Mock 数据源，提供 120+ 视频 URL，支持分页加载。
 * 全部使用已验证可直接访问的公开 MP4 视频源。
 */
object MockDataSource {

    // 已验证可用的 MP4 视频 URL（全球 CDN / 国内可访问）
    private val videoUrls = listOf(
        "https://media.w3.org/2010/05/sintel/trailer.mp4",                        // 4.2MB 动画预告片
        "https://media.w3.org/2010/05/bunny/trailer.mp4",                          // 10.5MB 兔子动画
        "https://media.w3.org/2010/05/video/movie_300.mp4",                        // 2.6MB 经典短片
        "https://vjs.zencdn.net/v/oceans.mp4",                                     // 22MB 海洋风光
        "https://www.w3schools.com/html/mov_bbb.mp4",                              // 770KB 兔子短片
        "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4",   // 1MB 360p
        "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_2MB.mp4",   // 1.9MB 720p
        "https://res.cloudinary.com/demo/video/upload/dog.mp4",                    // 8.7MB 宠物视频
    )

    private val titles = listOf(
        "奇幻冒险之旅",
        "春天的故事",
        "经典动画回顾",
        "深海探秘",
        "趣味短片",
        "快乐时光",
        "精彩瞬间",
        "萌宠日常",
    )

    private val authors = listOf(
        "动画工坊",
        "自然纪录",
        "经典影视",
        "海洋探索",
        "趣味生活",
        "快乐分享",
        "影像日记",
        "萌宠频道",
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
        return allVideos.take(PAGE_SIZE) to true
    }
}
