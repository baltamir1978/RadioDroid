package es.altamirano.fmdroid.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.fm
import es.altamirano.fmdroid.playback.LAST_STATION
import es.altamirano.fmdroid.playback.PREFS
import es.altamirano.fmdroid.playback.RadioService
import es.altamirano.fmdroid.playback.toRequestItem
import es.altamirano.fmdroid.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

// --- «Emisora»: carátula, canción, la línea que suena y play/pausa -----------------------------

class NowPlayingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = context.fm.widgets
        provideContent {
            val m by feed.nowModel.collectAsState()
            GlanceTheme { NowPlayingContent(m) }
        }
    }

    companion object {
        val SMALL = DpSize(120.dp, 60.dp)
        val WIDE = DpSize(250.dp, 80.dp)
        val TALL = DpSize(250.dp, 150.dp)
    }
}

@Composable
private fun NowPlayingContent(m: NowModel) {
    val size = LocalSize.current
    val context = LocalContext.current
    val wide = size.width >= NowPlayingWidget.WIDE.width
    val tall = size.height >= NowPlayingWidget.TALL.height
    WidgetBox {
        Row(GlanceModifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val artSize = if (tall) 96.dp else 56.dp
            Artwork(m, artSize)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(m.track ?: m.station ?: context.getString(R.string.nothing_playing), maxLines = if (tall) 2 else 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp))
                val sub = listOfNotNull(m.artist, if (m.track != null) m.station else null).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                if (wide && m.current != null) {
                    Spacer(GlanceModifier.height(4.dp))
                    Text(m.current, maxLines = if (tall) 3 else 1,
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                }
            }
            if (m.station != null || wide) PlayButton(m)
        }
    }
}

// --- «Letra»: pequeño (la línea que suena) y mediano (con la anterior y la siguiente) -------

class LyricsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = context.fm.widgets
        provideContent {
            val m by feed.nowModel.collectAsState()
            GlanceTheme { LyricsContent(m) }
        }
    }

    companion object {
        val SMALL = DpSize(180.dp, 60.dp)
        val MEDIUM = DpSize(250.dp, 140.dp)
    }
}

@Composable
private fun LyricsContent(m: NowModel) {
    val context = LocalContext.current
    val medium = LocalSize.current.height >= LyricsWidget.MEDIUM.height
    WidgetBox {
        Column(GlanceModifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (medium && m.track != null) {
                Text(listOfNotNull(m.track, m.artist).joinToString(" · "), maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center))
                Spacer(GlanceModifier.height(6.dp))
            }
            val faded = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
            when {
                m.message != null -> Text(context.getString(m.message), maxLines = 2, style = faded)
                else -> {
                    if (medium) m.previous?.let { Text(it, maxLines = 1, style = faded) }
                    Text(m.current ?: "♪", maxLines = if (medium) 2 else 3, style = TextStyle(
                        color = GlanceTheme.colors.primary, fontSize = if (medium) 18.sp else 16.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                    if (medium) m.next?.let { Text(it, maxLines = 1, style = faded) }
                }
            }
        }
    }
}

// --- «Emisoras»: accesos directos en rejilla --------------------------------------------------

class StationsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = context.fm.widgets
        provideContent {
            val m by feed.stationsModel.collectAsState()
            GlanceTheme { StationsContent(m) }
        }
    }
}

@Composable
private fun StationsContent(m: StationsModel) {
    WidgetBox {
        LazyVerticalGrid(GridCells.Adaptive(76.dp), GlanceModifier.fillMaxSize().padding(6.dp)) {
            items(m.tiles, itemId = { it.id.hashCode().toLong() }) { tile ->
                val current = tile.id == m.currentId
                Column(
                    GlanceModifier.fillMaxWidth().padding(4.dp).cornerRadius(12.dp)
                        .background(if (current) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.widgetBackground)
                        .clickable(actionRunCallback<PlayStation>(actionParametersOf(StationKey to tile.id))),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(GlanceModifier.height(6.dp))
                    Logo(tile.logo, 44.dp)
                    Text(tile.name, maxLines = 1, modifier = GlanceModifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = TextStyle(fontSize = 11.sp, textAlign = TextAlign.Center,
                            color = if (current) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface))
                }
            }
        }
    }
}

// --- Piezas comunes ---------------------------------------------------------------------------

@Composable
private fun WidgetBox(content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Artwork(m: NowModel, size: androidx.compose.ui.unit.Dp) = Logo(m.art, size)

@Composable
private fun Logo(bitmap: android.graphics.Bitmap?, size: androidx.compose.ui.unit.Dp) {
    Box(GlanceModifier.size(size).cornerRadius(10.dp).background(GlanceTheme.colors.secondaryContainer),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(ImageProvider(bitmap), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(ImageProvider(R.drawable.ic_widget_radio), null, GlanceModifier.size(size * 0.55f),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
        }
    }
}

@Composable
private fun PlayButton(m: NowModel) {
    val context = LocalContext.current
    val playing = m.playing || m.busy
    Box(
        GlanceModifier.size(48.dp).cornerRadius(24.dp).background(GlanceTheme.colors.primary)
            .clickable(actionRunCallback<TogglePlayback>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            context.getString(if (playing) R.string.pause else R.string.play), GlanceModifier.size(26.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary))
    }
}

// --- Acciones: hablan con el servicio como lo hace la app --------------------------------------

private val StationKey = ActionParameters.Key<String>("station")

private suspend fun withController(context: Context, block: (MediaController) -> Unit) =
    withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, RadioService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            block(controller)
            delay(1000)   // que la orden llegue antes de soltar la conexión
        } finally {
            controller.release()
        }
    }

class TogglePlayback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { c ->
            when {
                c.playWhenReady -> c.pause()
                c.mediaItemCount == 0 -> {
                    // Nada cargado (la app no se ha abierto): la última emisora, o la primera.
                    val fm = context.fm
                    val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_STATION, null)
                    val station = id?.let { fm.stations.find(it) } ?: fm.stations.stations.value.firstOrNull()
                    if (station != null) { c.setMediaItem(station.toRequestItem()); c.prepare(); c.play() }
                }
                else -> { c.prepare(); c.play() }
            }
        }
    }
}

class PlayStation : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val station = parameters[StationKey]?.let { context.fm.stations.find(it) } ?: return
        withController(context) { c ->
            c.setMediaItem(station.toRequestItem())
            c.prepare()
            c.play()
        }
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

class LyricsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LyricsWidget()
}

class StationsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StationsWidget()
}
