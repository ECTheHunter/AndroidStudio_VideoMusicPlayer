package com.example.videomusicplayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

data class BottomNavItem(
    // Text below icon
    val label: String,
    // Icon
    val icon: ImageVector,
    // Route to the specific screen
    val route:String,
)
object Constants {
    val BottomNavItems = listOf(
        BottomNavItem(
            label = "Music",
            icon = Icons.Filled.LibraryMusic,
            route = "Music"
        ),
        BottomNavItem(
            label = "Video",
            icon = Icons.Filled.VideoLibrary,
            route = "Video"
        )
    )
}