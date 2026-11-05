@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.fmdroid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.core.SongLyrics
import es.altamirano.fmdroid.fm
import es.altamirano.fmdroid.playback.Connection
import es.altamirano.fmdroid.playback.LyricsState
import es.altamirano.fmdroid.playback.NowState
import kotlinx.coroutines.delay
import java.util.Locale

/** Cuánto deja de seguir la canción la letra cuando se arrastra a mano. */
private const val FOLLOW_PAUSE_MS = 4000L
private const val NUDGE = 0.5

/**
 * El reproductor de RadioApp: degradado menta, «EN DIRECTO» arriba, la carátula grande y el
 * botón redondo. En el móvil, «Ver letra» cambia la carátula por la letra; en un plegable abierto
 * o una tableta van las dos lado a lado.
 */
@Composable
fun NowPlayingScreen(
    now: NowState,
    player: PlayerController,
    sideBySide: Boolean,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    now.station ?: return
    val colors = MaterialTheme.colorScheme
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.surfaceContainer, colors.background)))
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        // La barra: «EN DIRECTO» a la izquierda y la flecha para bajar el reproductor.
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveBadge(visible = now.connection == Connection.PLAYING)
            Spacer(Modifier.weight(1f))
            if (onClose != null) IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.back), tint = colors.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Ancho sólo no basta: un móvil tumbado es ancho pero demasiado bajo.
            val boxWidth = maxWidth
            val wide = sideBySide && maxWidth >= 640.dp && maxHeight >= 420.dp
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    PlayerColumn(now, player, showHero = true, wide = true, showLyrics = false, onToggleLyrics = {},
                        onClose = onClose, modifier = Modifier.width(minOf(420.dp, boxWidth * 0.45f)).fillMaxSize())
                    LyricsPanel(now, Modifier.weight(1f).fillMaxSize().padding(vertical = 24.dp))
                }
            } else {
                PlayerColumn(now, player, showHero = !showLyrics, wide = false, showLyrics = showLyrics,
                    onToggleLyrics = { showLyrics = !showLyrics }, onClose = onClose, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun LiveBadge(visible: Boolean) {
    val brand = MaterialTheme.colorScheme.primary
    Row(Modifier.alpha(if (visible) 1f else 0f), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(brand))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.live).uppercase(Locale.getDefault()), color = brand,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun PlayerColumn(
    now: NowState, player: PlayerController, showHero: Boolean, wide: Boolean, showLyrics: Boolean,
    onToggleLyrics: () -> Unit, onClose: (() -> Unit)?, modifier: Modifier,
) {
    val station = now.station ?: return
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showHero) {
            Spacer(Modifier.weight(1f))
            // La carátula de la canción si se sabe, si no el logo de la emisora; funde al cambiar.
            Box(Modifier.shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = colors.scrim, spotColor = colors.scrim)) {
                Crossfade(now.song?.artworkUrl, label = "art") { art ->
                    if (art != null) {
                        Box(Modifier.size(200.dp).clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainer)) {
                            StationLogo(station.name, station.logoURL, 200.dp, corner = 0.12f)
                            AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    } else {
                        StationLogo(station.name, station.logoURL, 200.dp, corner = 0.12f)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        } else {
            LyricsPanel(now, Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp))
        }

        // La canción manda cuando se sabe; si no, el nombre de la emisora.
        AnimatedContent(now.song, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "song",
            modifier = Modifier.padding(horizontal = 32.dp)) { song ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (song != null) {
                    Text(song.track, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    song.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    // La emisora sigue a la vista aunque la carátula sea la de la canción.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        StationLogo(station.name, station.logoURL, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(station.name, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                } else {
                    Text(station.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    connectionText(now.connection)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (!wide) {
            TextButton(onClick = onToggleLyrics, modifier = Modifier.padding(top = 4.dp)) {
                Icon(if (showLyrics) Icons.Filled.Image else Icons.Filled.FormatQuote, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (showLyrics) R.string.hide_lyrics else R.string.view_lyrics), fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(if (showHero) 32.dp else 16.dp))

        // Parar · el botón grande · Shazam
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            IconButton(onClick = { player.stop(); onClose?.invoke() }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Stop, stringResource(R.string.stop), tint = colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(30.dp))
            }
            BigPlayButton(now, player)
            IdentifyButton(now)
        }
        // Sólo se dice que no se encontró si no hay otra cosa: con el título de la emisora sobraría.
        val message = now.identifyMessage
        if (message != null && now.song == null) {
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp))
        }
        Spacer(Modifier.height(40.dp))
        if (showHero) Spacer(Modifier.weight(1f))
    }
}

/** «Identificar canción»: pregunta a Shazam por lo que suena; mientras escucha, se ilumina. */
@Composable
private fun IdentifyButton(now: NowState) {
    val engine = LocalContext.current.fm.nowPlaying
    val colors = MaterialTheme.colorScheme
    val label = stringResource(if (now.identifying) R.string.identifying else R.string.identify_song)
    IconButton(onClick = { engine.identifyNow() }, enabled = now.connection == Connection.PLAYING || now.identifying,
        modifier = Modifier.size(52.dp).semantics { selected = now.identifying }) {
        if (now.identifying) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.dp, color = colors.primary)
                Icon(Icons.Filled.GraphicEq, label, tint = colors.primary, modifier = Modifier.size(20.dp))
            }
        } else {
            Icon(Icons.Filled.GraphicEq, label, tint = colors.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun BigPlayButton(now: NowState, player: PlayerController) {
    val brand = MaterialTheme.colorScheme.primary
    val busy = now.connection == Connection.CONNECTING || now.connection == Connection.RECONNECTING
    val playing = now.connection == Connection.PLAYING || busy
    val label = stringResource(when {
        now.connection == Connection.RECONNECTING -> R.string.reconnecting
        now.connection == Connection.CONNECTING -> R.string.loading
        playing -> R.string.pause
        else -> R.string.play
    })
    Box(
        Modifier.size(76.dp)
            .shadow(14.dp, CircleShape, ambientColor = brand, spotColor = brand)
            .clip(CircleShape).background(brand)
            .clickable(onClickLabel = label) { player.toggle() },
        contentAlignment = Alignment.Center,
    ) {
        // El símbolo con el color del fondo y no blanco: así pasa 5:1 en claro y en oscuro.
        val glyph = MaterialTheme.colorScheme.onPrimary
        if (busy) CircularProgressIndicator(Modifier.size(30.dp), color = glyph, strokeWidth = 3.dp)
        else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, label, tint = glyph,
            modifier = Modifier.size(40.dp))
    }
}

// --- La letra -------------------------------------------------------------------------------

@Composable
private fun LyricsPanel(now: NowState, modifier: Modifier) {
    val synced = (now.lyrics as? LyricsState.Found)?.lyrics?.synced?.isNotEmpty() == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lyrics_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (synced) SyncAdjuster(now.clock.offset)
        }
        LyricsContent(now)
    }
}

@Composable
private fun LyricsContent(now: NowState) {
    val engine = LocalContext.current.fm.nowPlaying
    if (now.song == null) {
        Note(stringResource(R.string.lyrics_wait_title))
        return
    }
    when (val l = now.lyrics) {
        LyricsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lyrics_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LyricsState.Failed -> Column {
            Note(stringResource(R.string.lyrics_failed))
            TextButton(onClick = { engine.retryLyrics() }) { Text(stringResource(R.string.lyrics_retry)) }
        }
        is LyricsState.Found -> when {
            l.lyrics.instrumental -> Note(stringResource(R.string.lyrics_instrumental))
            l.lyrics.synced.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint(stringResource(if (now.clock.exact) R.string.lyrics_hint_synced else R.string.lyrics_hint_unplaced))
                SyncedLyrics(now, l.lyrics)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint(stringResource(R.string.lyrics_hint_plain))
                LazyColumn { item {
                    Text(l.lyrics.plain.joinToString("\n"), style = MaterialTheme.typography.titleMedium, lineHeight = 30.sp)
                } }
            }
        }
        else -> Note(stringResource(R.string.lyrics_none))
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * La letra que sigue la canción. Como en iOS, mientras no se sabe por dónde va (se sintonizó a
 * mitad) no se resalta nada: tocar la línea que suena la sitúa.
 */
@Composable
private fun SyncedLyrics(now: NowState, lyrics: SongLyrics) {
    val engine = LocalContext.current.fm.nowPlaying
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var elapsed by remember { mutableStateOf<Double?>(null) }
    var followPausedUntil by remember { mutableLongStateOf(0L) }
    val dragged by list.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(lyrics, now.clock) {
        while (true) {
            elapsed = if (now.clock.exact) engine.elapsed() else null
            delay(250)
        }
    }
    LaunchedEffect(dragged) {
        if (dragged) followPausedUntil = System.currentTimeMillis() + FOLLOW_PAUSE_MS
    }
    val current = elapsed?.let { lyrics.lineIndex(it) }
    LaunchedEffect(current) {
        if (current != null && System.currentTimeMillis() > followPausedUntil) {
            val half = list.layoutInfo.viewportSize.height / 2
            list.animateScrollToItem(current, -(half - 60))
        }
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(lyrics.synced) { i, line ->
            val isCurrent = i == current
            val past = current != null && i < current
            Text(
                line.text.ifEmpty { "♪" },
                fontSize = 22.sp, lineHeight = 28.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isCurrent -> colors.onSurface
                    past -> colors.onSurface.copy(alpha = 0.3f)
                    else -> colors.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // Tocar la línea que se está cantando sincroniza la letra.
                    .clickable { engine.syncToLine(line.time); followPausedUntil = 0 }
                    .semantics { selected = isCurrent },
            )
        }
    }
}

/** − / ±0 s / + / ↺: la letra antes o después en esta emisora, de medio en medio segundo. */
@Composable
private fun SyncAdjuster(offset: Double) {
    val engine = LocalContext.current.fm.nowPlaying
    val brand = MaterialTheme.colorScheme.primary
    val text = if (offset == 0.0) "±0 s" else String.format(Locale.getDefault(), "%+.1f s", offset)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { engine.nudge(-NUDGE) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, stringResource(R.string.lyrics_later), tint = brand)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 48.dp))
        IconButton(onClick = { engine.nudge(NUDGE) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.lyrics_earlier), tint = brand)
        }
        // Tocar una línea deja cifras como +1,3 s que los medios segundos no devuelven a cero.
        IconButton(onClick = { engine.resetOffset() }, enabled = offset != 0.0, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Replay, stringResource(R.string.lyrics_reset),
                tint = if (offset != 0.0) brand else brand.copy(alpha = 0.3f))
        }
    }
}
