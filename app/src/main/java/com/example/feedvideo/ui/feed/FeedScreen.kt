package com.example.feedvideo.ui.feed

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.feedvideo.ui.player.VideoPlayerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Feed 列表主界面 — 全屏视频流，支持下拉刷新和上拉加载更多。
 */
@Composable
fun FeedScreen(
    viewModel: FeedViewModel = viewModel()
) {
    val videos by viewModel.videos.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val showComments by viewModel.showComments.collectAsState()
    val comments by viewModel.comments.collectAsState()

    val listState = rememberLazyListState()

    // 监听滚动位置，更新当前播放视频
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { index ->
                val video = videos.getOrNull(index)
                Log.i("FeedScreen", "Switching to video [$index]: ${video?.title ?: "N/A"}")
                viewModel.switchToVideo(index)
            }
    }

    // 检测是否滚动到底部，触发加载更多
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3
        }
            .distinctUntilChanged()
            .collectLatest { nearEnd ->
                if (nearEnd && hasMore && !isLoadingMore) {
                    viewModel.loadMore()
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                videos,
                key = { _, video -> video.id }
            ) { index, video ->
                val isCurrent = index == currentIndex

                Box(
                    modifier = Modifier
                        .fillParentMaxSize()
                        .background(Color.Black)
                ) {
                    if (isCurrent) {
                        // 使用代理 URL 播放
                        val proxyUrl = if (video.url.startsWith("file:///android_asset/")) {
                            video.url // Asset 直接由 Player 处理，不走代理
                        } else {
                            viewModel.videoProxy.getProxyUrl(video.url)
                        }

                        VideoPlayerView(
                            player = viewModel.player,
                            videoUrl = proxyUrl,
                            isCurrentVideo = true,
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // 全屏点击手势
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { viewModel.player.togglePlayPause() },
                                        onLongPress = { viewModel.player.setSpeed(2.0f) }
                                    )
                                }
                        )
                    }

                    // 视频信息叠加层
                    VideoInfoOverlay(
                        title = video.title,
                        author = video.author,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 80.dp)
                    )

                    // 操作按钮
                    ActionButtons(
                        isLiked = video.isLiked,
                        likeCount = video.likeCount,
                        commentCount = video.commentCount,
                        onLikeClick = { viewModel.toggleLike(video.id) },
                        onCommentClick = { viewModel.toggleComments() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 80.dp)
                    )
                }
            }
        }

        // 下拉刷新指示器
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp),
                color = Color.White
            )
        }

        // 评论面板
        if (showComments) {
            CommentsBottomSheet(
                comments = comments,
                onDismiss = { viewModel.toggleComments() },
                onSubmit = { content ->
                    videos.getOrNull(currentIndex)?.let { 
                        viewModel.addComment(it.id, content) 
                    }
                }
            )
        }
    }
}

@Composable
private fun VideoInfoOverlay(title: String, author: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("@$author", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(title, color = Color.White, fontSize = 16.sp, maxLines = 2)
    }
}

@Composable
private fun ActionButtons(
    isLiked: Boolean,
    likeCount: Int,
    commentCount: Int,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ActionButton(if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, formatCount(likeCount), if (isLiked) Color.Red else Color.White, onLikeClick)
        ActionButton(Icons.Default.ChatBubble, formatCount(commentCount), Color.White, onCommentClick)
        ActionButton(Icons.Default.Share, "分享", Color.White) {}
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun CommentsBottomSheet(comments: List<String>, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)).clickable { onDismiss() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.BottomCenter).background(Color.DarkGray, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).clickable(false) {}.padding(16.dp)) {
            Text("评论 (${comments.size})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f)) {
                items(comments.size) { Text(comments[it], color = Color.White, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextField(text, { text = it }, placeholder = { Text("写评论...", color = Color.Gray) }, modifier = Modifier.weight(1f), colors = TextFieldDefaults.textFieldColors(textColor = Color.White))
                TextButton({ if (text.isNotBlank()) { onSubmit(text); text = "" } }) { Text("发送", color = Color.White) }
            }
        }
    }
}

private fun formatCount(count: Int): String = when {
    count >= 10000 -> "${count / 10000}.${(count % 10000) / 1000}万"
    else -> count.toString()
}
