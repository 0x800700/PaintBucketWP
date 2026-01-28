package com.example.paintbucketwp

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.lennycat.wallpaper.GLPaintWallpaperService
import com.example.paintbucketwp.ui.theme.PaintBucketWPTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaintBucketWPTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WallpaperSetupScreen(
                        onSetWallpaper = { launchWallpaperPicker() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun launchWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, GLPaintWallpaperService::class.java)
            )
        }
        startActivity(intent)
    }
}

@Composable
fun WallpaperSetupScreen(onSetWallpaper: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Paint Bucket Live Wallpaper",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Tap the button below to set the live wallpaper.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSetWallpaper) {
            Text("Set Live Wallpaper")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WallpaperSetupPreview() {
    PaintBucketWPTheme {
        WallpaperSetupScreen(onSetWallpaper = {})
    }
}
