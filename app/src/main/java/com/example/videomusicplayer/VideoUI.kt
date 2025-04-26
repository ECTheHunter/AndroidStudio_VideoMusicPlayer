package com.example.videomusicplayer

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.database
import com.google.firebase.database.ktx.database


data class VideoItem(
    val id: Long,
    val name: String,
    val duration: Long,
    val uri: Uri
)

@Composable
fun VideoScreen() {
    val context = LocalContext.current
    var videoItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var selectedVideo by remember { mutableStateOf<VideoItem?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    val firebaseAnalytics = remember { Firebase.analytics }

    // Function to log video events
    fun logVideoEvent(videoName: String, eventType: String, duration: Long = 0) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, videoName.hashCode().toString())
            putString(FirebaseAnalytics.Param.ITEM_NAME, videoName)
            putString(FirebaseAnalytics.Param.CONTENT_TYPE, "video")
            putString("event_type", eventType)
            if (duration > 0) {
                putLong("duration_ms", duration)
            }
        }
        firebaseAnalytics.logEvent("video_interaction", bundle)
    }
    // ExoPlayer setup
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                }
            })
        }
    }

    // Release player when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            // 1. Stop playback if active
            if (exoPlayer.isPlaying) {
                exoPlayer.stop()
            }

            // 2. Release all player resources
            exoPlayer.release()

            // 3. Clear media items
            exoPlayer.clearMediaItems()

            // 4. Nullify references (helps with garbage collection)
            selectedVideo = null
            videoItems = emptyList()

            // Optional: Log cleanup completion
            android.util.Log.d("VideoScreen", "All resources released")
        }
    }

    // Update player when selected video changes
    LaunchedEffect(selectedVideo) {
        selectedVideo?.let { video ->
            exoPlayer.setMediaItem(MediaItem.fromUri(video.uri))
            logVideoEvent(video.name, "selected")
            exoPlayer.prepare()
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadVideos(context) { videos ->
                videoItems = videos
                fetchFirebaseVideos { firebaseVideos ->
                    videoItems = firebaseVideos + videoItems
                }
            }
        }

    }

    // Get videos when composition starts
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)

        fetchFirebaseVideos { firebaseVideos ->
            // Merge local videos with firebase videos
            videoItems = videoItems + firebaseVideos
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {
        if (isFullscreen) {
            // Fullscreen player view
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        player = exoPlayer
                        useController = true
                        setFullscreenButtonClickListener {
                            isFullscreen = false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Normal player view
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .fillMaxHeight()
                ) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                player = exoPlayer
                                useController = true
                                setFullscreenButtonClickListener {
                                    isFullscreen = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Custom fullscreen button (as backup)
                    IconButton(
                        onClick = { isFullscreen = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Enter fullscreen",
                            tint = Color.White
                        )
                    }
                }

                // List of available videos
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                ) {
                    items(videoItems) { video ->
                        VideoListItem(
                            video = video,
                            isSelected = selectedVideo?.id == video.id,
                            onVideoSelected = { selectedVideo = video }
                        )
                    }
                }
            }
        }
    }
}

// Extension function to handle fullscreen button clicks
fun PlayerView.setFullscreenButtonClickListener(listener: () -> Unit) {
    setFullscreenButtonClickListener { _ ->
        listener()
    }
}

@Composable
fun VideoListItem(
    video: VideoItem,
    isSelected: Boolean,
    onVideoSelected: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        onClick = onVideoSelected,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDuration(video.duration),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
fun fetchFirebaseVideos(onVideosFetched: (List<VideoItem>) -> Unit) {
    val database: DatabaseReference = Firebase.database.reference
    val videoList = mutableListOf<VideoItem>()

    database.child("video").get().addOnSuccessListener { snapshot ->
        snapshot.children.forEach { child ->
            val id = child.child("id").getValue(Long::class.java) ?: 0L
            val title = child.child("title").getValue(String::class.java) ?: ""
            val uri = child.child("uri").getValue(String::class.java) ?: ""
            val duration = child.child("duration").getValue(Long::class.java) ?: 0L

            if (title.isNotBlank() && uri.isNotBlank()) {
                videoList.add(
                    VideoItem(
                        id = id,
                        name = title,
                        duration = duration * 1000,
                        uri = Uri.parse(uri)
                    )
                )
            }
        }
        onVideosFetched(videoList)
    }.addOnFailureListener { exception ->
        // You can handle errors if needed
    }
}
fun formatDuration(duration: Long): String {
    val seconds = (duration / 1000) % 60
    val minutes = (duration / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}

fun loadVideos(context: Context, onVideosLoaded: (List<VideoItem>) -> Unit) {
    val videoList = mutableListOf<VideoItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATA
    )
    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn)
            val duration = cursor.getLong(durationColumn)
            val uri = Uri.parse(cursor.getString(dataColumn))

            videoList.add(VideoItem(id, name, duration, uri))
        }
    }
    onVideosLoaded(videoList)
}