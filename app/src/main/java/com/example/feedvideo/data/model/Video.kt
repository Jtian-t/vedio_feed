package com.example.feedvideo.data.model

data class Video(
    val id: String,
    val url: String,
    val title: String,
    val author: String,
    val coverUrl: String = "",
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val duration: Long = 0L,
    val isLiked: Boolean = false
)
