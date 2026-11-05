package es.altamirano.fmdroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
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

    // Shazam sólo identifica solo con la app a la vista, por los datos móviles.
    override fun onStart() {
        super.onStart()
        fm.nowPlaying.foreground = true
    }

    override fun onStop() {
        fm.nowPlaying.foreground = false
        super.onStop()
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
