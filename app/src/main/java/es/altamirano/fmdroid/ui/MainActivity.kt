package es.altamirano.fmdroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.fm

class MainActivity : ComponentActivity() {
    private lateinit var player: PlayerController

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = PlayerController(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            FmTheme {
                FmScreen(player, calculateWindowSizeClass(this).widthSizeClass)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    /** `radiodroid://play?u=<url>&n=<nombre>`: desde un acceso directo, un atajo o un enlace. */
    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "radiodroid" || data.host != "play") return
        val url = data.getQueryParameter("u")?.takeIf { it.startsWith("http") } ?: return
        val station = fm.stations.findByUrl(url)
            ?: Station(name = data.getQueryParameter("n") ?: url, streamURL = url)
        player.play(station)
    }
}

@Composable
fun FmTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}
