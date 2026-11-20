package com.takemaster.studio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor
import com.takemaster.studio.player.CompPlayerController
import com.takemaster.studio.ui.StudioScreen

class MainActivity : ComponentActivity() {

    private var playerController: CompPlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            val controller = remember {
                CompPlayerController(context = applicationContext).also {
                    playerController = it
                }
            }
            DisposableEffect(Unit) {
                onDispose {
                    controller.release()
                    if (playerController === controller) playerController = null
                }
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ComposeColor(0xFF3B82F6),
                    onPrimary = ComposeColor.White,
                    background = ComposeColor(0xFF0A0E17),
                    surface = ComposeColor(0xFF121826)
                )
            ) {
                StudioScreen(playerController = controller)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        playerController?.pause()
    }
}
