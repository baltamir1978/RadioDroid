@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.fmdroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@Composable
fun NowPlayingScreen(
    now: NowState,
    player: PlayerController,
    sideBySide: Boolean,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val station = now.station ?: return
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (sideBySide) {
            // Plegable abierto: reproductor y letra lado a lado.
            Row(Modifier.padding(padding).fillMaxSize()) {
                PlayerPanel(now, player, Modifier.weight(1f).padding(24.dp), big = true)
                LyricsPanel(now, Modifier.weight(1.2f).fillMaxSize())
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                PlayerPanel(now, player, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), big = false)
                LyricsPanel(now, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PlayerPanel(now: NowState, player: PlayerController, modifier: Modifier, big: Boolean) {
    val station = now.station ?: return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = if (big) 360.dp else 180.dp).fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))) {
            Logo(null, 1000)
            AsyncImage(model = now.song?.artworkUrl ?: station.logoURL, contentDescription = null,
                modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.size(12.dp))
        Text(now.song?.track ?: stringResource(R.string.no_song), style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(connectionText(now.connection) ?: now.song?.artist.orEmpty(),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { player.stop() }) { Icon(Icons.Filled.Stop, stringResource(R.string.stop)) }
            val busy = now.connection == Connection.CONNECTING || now.connection == Connection.RECONNECTING
            val playing = now.connection == Connection.PLAYING || busy
            FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(64.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.pause else R.string.play), Modifier.size(36.dp))
            }
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun LyricsPanel(now: NowState, modifier: Modifier) {
    val engine = LocalContext.current.fm.nowPlaying
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val l = now.lyrics) {
            LyricsState.None -> Message(stringResource(if (now.song == null) R.string.no_song else R.string.lyrics_none))
            LyricsState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.size(12.dp))
                Message(stringResource(R.string.lyrics_loading))
            }
            LyricsState.NotFound -> Message(stringResource(R.string.lyrics_none))
            LyricsState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Message(stringResource(R.string.lyrics_failed))
                TextButton(onClick = { engine.retryLyrics() }) { Text(stringResource(R.string.lyrics_retry)) }
            }
            is LyricsState.Found -> when {
                l.lyrics.instrumental -> Message(stringResource(R.string.lyrics_instrumental))
                l.lyrics.synced.isEmpty() -> PlainLyrics(l.lyrics)
                else -> SyncedLyrics(now, l.lyrics)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
}

@Composable
private fun PlainLyrics(lyrics: SongLyrics) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item { Text(stringResource(R.string.lyrics_unsynced), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        itemsIndexed(lyrics.plain) { _, line ->
            Text(line, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun SyncedLyrics(now: NowState, lyrics: SongLyrics) {
    val engine = LocalContext.current.fm.nowPlaying
    val list = rememberLazyListState()
    var elapsed by remember { mutableStateOf<Double?>(null) }
    var followPausedUntil by remember { mutableLongStateOf(0L) }
    val dragged by list.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(lyrics, now.clock) {
        while (true) {
            elapsed = engine.elapsed()
            delay(200)
        }
    }
    LaunchedEffect(dragged) {
        if (dragged) followPausedUntil = System.currentTimeMillis() + FOLLOW_PAUSE_MS
    }
    val current = elapsed?.let { lyrics.lineIndex(it) }
    LaunchedEffect(current) {
        if (current != null && System.currentTimeMillis() > followPausedUntil) {
            val third = list.layoutInfo.viewportSize.height / 3
            list.animateScrollToItem(current, -third)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            item { Spacer(Modifier.size(24.dp)) }
            itemsIndexed(lyrics.synced) { i, line ->
                val isCurrent = i == current
                Text(
                    line.text.ifEmpty { "♪" },
                    style = if (isCurrent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (current != null && i < current) 0.5f else 0.85f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        // Tocar la línea que se está cantando sincroniza la letra.
                        .clickable { engine.syncToLine(line.time); followPausedUntil = 0 }
                        .semantics { selected = isCurrent }
                        .padding(vertical = 6.dp),
                )
            }
            item { Spacer(Modifier.size(200.dp)) }
        }
        OffsetBar(now.clock.offset)
    }
}

/** − / + / ↺: el ajuste de la letra de esta emisora, que se recuerda. */
@Composable
private fun OffsetBar(offset: Double) {
    val engine = LocalContext.current.fm.nowPlaying
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { engine.nudge(-NUDGE) }) { Icon(Icons.Filled.Remove, stringResource(R.string.lyrics_later)) }
        Text(stringResource(R.string.lyrics_offset, String.format(Locale.getDefault(), "%+.1f", offset)),
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 64.dp),
            textAlign = TextAlign.Center)
        IconButton(onClick = { engine.nudge(NUDGE) }) { Icon(Icons.Filled.Add, stringResource(R.string.lyrics_earlier)) }
        IconButton(onClick = { engine.resetOffset() }, enabled = offset != 0.0) {
            Icon(Icons.Filled.Restore, stringResource(R.string.lyrics_reset))
        }
    }
}
