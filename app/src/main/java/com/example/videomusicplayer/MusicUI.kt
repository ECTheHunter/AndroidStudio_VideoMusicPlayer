package com.example.videomusicplayer
import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.database
import com.google.firebase.database.ktx.database


data class Song(val title: String, val path: String)

@Composable
fun MusicScreen() {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    var currentSongIndex by remember { mutableStateOf(0) }
    var currentSong by remember { mutableStateOf("Select a song") }
    var isPlaying by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableStateOf(0f) }
    var currentDuration by remember { mutableStateOf(0) }
    var totalDuration by remember { mutableStateOf(0) }
    var loopEnabled by remember { mutableStateOf(false) }
    var mediaPlayer: MediaPlayer? by remember { mutableStateOf(null) }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var focusRequest: AudioFocusRequest? = null
    val firebaseAnalytics = remember { Firebase.analytics }

    // Function to log song playback events
    fun logSongEvent(songTitle: String, eventType: String) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, songTitle.hashCode().toString())
            putString(FirebaseAnalytics.Param.ITEM_NAME, songTitle)
            putString(FirebaseAnalytics.Param.CONTENT_TYPE, "song")
            putString("event_type", eventType)
        }
        firebaseAnalytics.logEvent("song_playback", bundle)
    }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            mediaPlayer?.pause()
                            isPlaying = false
                        }

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            mediaPlayer?.pause()
                            isPlaying = false
                        }

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            mediaPlayer?.setVolume(0.3f, 0.3f) // Lower volume
                        }

                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mediaPlayer?.setVolume(1.0f, 1.0f)
                            if (!isPlaying) {
                                mediaPlayer?.start()
                                isPlaying = true
                            }
                        }
                    }
                }
                .build()

            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioManager.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            mediaPlayer?.pause()
                            isPlaying = false
                        }

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            mediaPlayer?.pause()
                            isPlaying = false
                        }

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            mediaPlayer?.setVolume(0.3f, 0.3f)
                        }

                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mediaPlayer?.setVolume(1.0f, 1.0f)
                            if (!isPlaying) {
                                mediaPlayer?.start()
                                isPlaying = true
                            }
                        }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            audioManager.abandonAudioFocus(null)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
            if (granted) {
                songs = getAllSongs(context)
            }
        }
    )

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
            songs = getAllSongs(context)
            fetchFirebaseSongs { firebaseSongs ->
                songs = firebaseSongs + songs  // Combine local and Firebase songs
            }
        } else {
            permissionLauncher.launch(permission)
        }
    }

    LaunchedEffect(currentSong) {
        if (currentSong != "Select a song") {
            logSongEvent(currentSong, "selected")
            mediaPlayer?.stop()
            mediaPlayer?.release()
            isPlaying = false
            sliderPosition = 0f
            currentDuration = 0
            if (requestAudioFocus()) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(getSongPath(currentSong, songs))
                    prepare()
                    start()
                    setOnPreparedListener {
                        totalDuration = it.duration
                        sliderPosition = 0f
                        mediaPlayer?.start()
                        isPlaying = true
                    }

                    setOnCompletionListener {
                        if (loopEnabled) {
                            seekTo(0)
                            start()
                        } else {
                            val nextIndex =
                                if (currentSongIndex + 1 >= songs.size) 0 else currentSongIndex + 1
                            if (nextIndex != currentSongIndex) {
                                currentSongIndex = nextIndex
                                currentSong = songs[currentSongIndex].title
                            } else {
                                isPlaying = false
                            }
                        }
                    }
                }
            }

        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Release the MediaPlayer when the composable is disposed
            mediaPlayer?.release()
            mediaPlayer = null

            // Release audio focus if granted
            releaseAudioFocus()

            // Any other cleanup logic goes here (e.g., handler removal if needed)
            handler.removeCallbacksAndMessages(null)
        }
    }
    // Update media player progress
    LaunchedEffect(mediaPlayer) {
        val updatePositionRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    currentDuration = it.currentPosition
                    sliderPosition = (currentDuration / totalDuration.toFloat()) * 100f
                    handler.postDelayed(this, 1000)
                }
            }
        }

        if (isPlaying) {
            handler.post(updatePositionRunnable)
        } else {
            handler.removeCallbacks(updatePositionRunnable)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasPermission) {
            return@Column
        }

        // Song List
        LazyColumn(
            modifier = Modifier
                .height(400.dp)
                .padding(16.dp)
        ) {
            items(songs) { song ->
                SongItem(song.title) {
                    currentSong = song.title
                    currentSongIndex = songs.indexOf(song)
                }
            }
        }

        Text(
            text = currentSong,
            fontSize = 24.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .horizontalScroll(rememberScrollState())
        )

        // Slider Row
        Row(
            modifier = Modifier.fillMaxWidth(0.95f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatDuration(currentDuration),
                modifier = Modifier.padding(end = 4.dp),
                fontSize = 16.sp
            )
            Slider(
                value = sliderPosition,
                onValueChange = { newPos ->
                    sliderPosition = newPos
                    mediaPlayer?.seekTo((newPos / 100f * totalDuration).toInt())
                },
                valueRange = 0f..100f,
                enabled = currentSong != "Select a song",
                modifier = Modifier.weight(1f)
            )
            Text(
                formatDuration(totalDuration),
                modifier = Modifier.padding(start = 4.dp),
                fontSize = 16.sp
            )
        }

        // Control Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LargeIconButton(
                icon = Icons.Filled.Shuffle,
                contentDescription = "Shuffle",
                onClick = {
                    if (songs.isNotEmpty()) {
                        val randomIndex = (songs.indices).random()
                        currentSong = songs[randomIndex].title
                    }
                }
            )
            LargeIconButton(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = "Previous Song",
                onClick = {
                    val previousIndex =
                        if (currentSongIndex + 1 >= songs.size) 0 else currentSongIndex + 1
                    currentSongIndex = previousIndex
                    currentSong = songs[currentSongIndex].title
                }
            )
            LargeIconButton(
                icon = if (isPlaying && currentSong != "Select a song") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                    } else {
                        mediaPlayer?.start()
                    }
                    isPlaying = !isPlaying
                }
            )
            LargeIconButton(
                icon = Icons.Filled.Replay,
                contentDescription = "Reset",
                onClick = {
                    mediaPlayer?.seekTo(0)
                    sliderPosition = 0f
                }
            )
            LargeIconButton(
                icon = Icons.Filled.SkipNext,
                contentDescription = "Next Song",
                onClick = {
                    val nextIndex =
                        if (currentSongIndex + 1 >= songs.size) 0 else currentSongIndex + 1
                    currentSongIndex = nextIndex
                    currentSong = songs[currentSongIndex].title
                }
            )
            LargeIconButton(
                icon = Icons.Filled.Repeat,
                contentDescription = "Loop",
                onClick = {
                    loopEnabled = !loopEnabled
                },
                tint = if (loopEnabled) Color.Green else Color.Gray
            )
        }
    }

}

fun getSongPath(title: String, songs: List<Song>): String {
    return songs.find { it.title == title }?.path ?: ""
}


fun formatDuration(duration: Int): String {
    val minutes = duration / 1000 / 60
    val seconds = duration / 1000 % 60
    return String.format("%02d:%02d", minutes, seconds)
}
fun fetchFirebaseSongs(onSongsFetched: (List<Song>) -> Unit) {
    val database: DatabaseReference = Firebase.database.reference
    val songList = mutableListOf<Song>()

    database.child("music").get().addOnSuccessListener { snapshot ->
        snapshot.children.forEach { child ->
            val title = child.child("title").getValue(String::class.java)
            val uri = child.child("uri").getValue(String::class.java)
            if (title != null && uri != null) {
                songList.add(Song(title, uri))
            }
        }
        onSongsFetched(songList)
    }.addOnFailureListener { exception ->
        // Handle error if needed
    }
}


@Composable
fun SongItem(song: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.LightGray)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(text = song, fontSize = 16.sp)
    }
}

fun getAllSongs(context: Context): List<Song> {
    val songList = mutableListOf<Song>()
    val contentResolver: ContentResolver = context.contentResolver
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.DATA
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val cursor = contentResolver.query(uri, projection, selection, null, null)

    cursor?.use {
        val titleColumn = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
        val dataColumn = it.getColumnIndex(MediaStore.Audio.Media.DATA)
        while (it.moveToNext()) {
            val title = it.getString(titleColumn)
            val path = it.getString(dataColumn)
            if (title != null && path != null) {
                songList.add(Song(title, path))
            }
        }
    }
    return songList
}

@Composable
fun LargeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.Black
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(48.dp),
            tint = tint
        )
    }
}

