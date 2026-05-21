package com.example.feedvideo.ui.feed

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.feedvideo.ui.player.VideoPlayerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Feed 列表主界面 — 全屏视频流，支持下拉刷新和上拉加载更多。
 * 使用 LazyColumn 实现全屏 item 布局。
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
                viewModel.switchToVideo(index)
            }
    }

    // 检测是否滚动到底部，触发加载更多
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3 // 还剩 3 个 item 时触发
        }
            .distinctUntilChanged()
            .collectLatest { nearEnd ->
                if (nearEnd && hasMore && !isLoadingMore) {
                    viewModel.loadMore()
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 视频列表
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
        ) {
            itemsIndexed(
                videos,
                key = { _, video -> video.id },
                contentType = { _, _ -> "video_item" }
            ) { index, video ->
                val isCurrent = index == currentIndex

                Box(
                    modifier = Modifier
                        .fillParentMaxSize()
                        .background(Color.Black)
                ) {
                    // 仅当前视频创建 SurfaceView，其他显示黑色占位
                    if (isCurrent) {
                        VideoPlayerView(
                            player = viewModel.player,
                            videoUrl = video.url,
                            isCurrentVideo = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // 点击暂停/播放（仅当前视频响应）
                    if (isCurrent) {
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

                    // 视频信息叠加层（左下角）
                    VideoInfoOverlay(
                        title = video.title,
                        author = video.author,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 80.dp)
                    )

                    // 操作按钮（右侧）
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

            // 加载更多指示器
            if (isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            // "没有更多了"
            if (!hasMore && videos.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有更多了",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // 下拉刷新指示器
        AnimatedVisibility(
            visible = isRefreshing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 48.dp)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 下拉刷新检测 — 在顶部区域添加手势
        if (listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset < 50
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.TopCenter)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { viewModel.refresh() }
                        )
                    }
            )
        }

        // 评论面板
        if (showComments) {
            CommentsBottomSheet(
                comments = comments,
                onDismiss = { viewModel.toggleComments() },
                onSubmit = { content ->
                    val currentVideo = videos.getOrNull(currentIndex)
                    if (currentVideo != null) {
                        viewModel.addComment(currentVideo.id, content)
                    }
                }
            )
        }
    }
}

/**
 * 视频信息叠加层 — 标题、作者
 */
@Composable
private fun VideoInfoOverlay(
    title: String,
    author: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "@$author",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2
        )
    }
}

/**
 * 操作按钮 — 点赞、评论、分享
 */
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
        // 点赞按钮
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onLikeClick() }
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "点赞",
                tint = if (isLiked) Color.Red else Color.White,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = formatCount(likeCount),
                color = Color.White,
                fontSize = 12.sp
            )
        }

        // 评论按钮
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onCommentClick() }
        ) {
            Icon(
                imageVector = Icons.Default.ChatBubble,
                contentDescription = "评论",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = formatCount(commentCount),
                color = Color.White,
                fontSize = 12.sp
            )
        }

        // 分享按钮
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "分享",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "分享",
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * 评论面板
 */
@Composable
private fun CommentsBottomSheet(
    comments: List<String>,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.BottomCenter)
                .background(
                    Color.DarkGray,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickable(enabled = false) {} // 阻止穿透
                .padding(16.dp)
        ) {
            Text(
                text = "评论 (${comments.size})",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 评论列表
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(comments.size) { index ->
                    Text(
                        text = comments[index],
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Divider(color = Color.Gray.copy(alpha = 0.3f))
                }

                if (comments.isEmpty()) {
                    item {
                        Text(
                            text = "暂无评论，快来说点什么吧~",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }

            // 评论输入框
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("写评论...", color = Color.Gray) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.textFieldColors(
                        textColor = Color.White,
                        backgroundColor = Color.Transparent,
                        cursorColor = Color.White
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSubmit(inputText)
                            inputText = ""
                        }
                    }
                ) {
                    Text("发送", color = Color.White)
                }
            }
        }
    }
}

/**
 * 格式化数字显示（1.2万、3.5万）
 */
private fun formatCount(count: Int): String {
    return when {
        count >= 10000 -> "${count / 10000}.${(count % 10000) / 1000}万"
        count >= 1000 -> "${count / 1000}.${(count % 1000) / 100}k"
        else -> count.toString()
    }
}
