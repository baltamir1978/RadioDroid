package es.altamirano.fmdroid.ui

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.core.matchStation
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
    /** Una emisora que llega en un enlace y no está en «Mis Emisoras»: se pregunta antes de ponerla. */
    private val asking = mutableStateOf<Station?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = PlayerController(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            FmTheme {
                FmScreen(player, calculateWindowSizeClass(this).widthSizeClass)
                asking.value?.let { station ->
                    AlertDialog(
                        onDismissRequest = { asking.value = null },
                        title = { Text(stringResource(R.string.play_link_title)) },
                        text = { Text(if (station.name == station.streamURL) station.streamURL else station.name + "\n" + station.streamURL) },
                        confirmButton = { TextButton(onClick = { asking.value = null; player.play(station) }) { Text(stringResource(R.string.play)) } },
                        dismissButton = { TextButton(onClick = { asking.value = null }) { Text(stringResource(R.string.cancel)) } },
                    )
                }
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
        // «Pon Radio 3» al asistente: la emisora que mejor casa con lo pedido.
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val list = fm.stations.stations.value
            val station = matchStation(intent.getStringExtra(SearchManager.QUERY), list.map { it.name })?.let { list[it] }
                ?: list.firstOrNull() ?: return
            player.play(station)
            return
        }
        val data = intent?.data ?: return
        if (data.scheme != "radiodroid" || data.host != "play") return
        val url = data.getQueryParameter("u")?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        // Las de «Mis Emisoras» (accesos directos, widgets) suenan sin más. Una dirección de fuera
        // puede venir de cualquier página web: antes de conectar, se pregunta.
        val own = fm.stations.findByUrl(url)
        if (own != null) player.play(own)
        else asking.value = Station(name = data.getQueryParameter("n")?.take(80) ?: url, streamURL = url)
    }
}
