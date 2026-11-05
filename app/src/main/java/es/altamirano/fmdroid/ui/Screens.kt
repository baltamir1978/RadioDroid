@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.fmdroid.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.data.ListenedSong
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.fm
import es.altamirano.fmdroid.net.NetworkException
import es.altamirano.fmdroid.net.RadioBrowser
import es.altamirano.fmdroid.playback.Connection
import es.altamirano.fmdroid.playback.NowState
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class Tab(val label: Int, val icon: ImageVector) {
    STATIONS(R.string.tab_stations, Icons.Filled.Radio),
    SEARCH(R.string.tab_search, Icons.Filled.Search),
    HISTORY(R.string.tab_history, Icons.Filled.History),
}

@Composable
fun FmScreen(player: PlayerController, width: WindowWidthSizeClass) {
    val app = LocalContext.current.fm
    val now by app.nowPlaying.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val wide = width != WindowWidthSizeClass.Compact
    val expanded = width == WindowWidthSizeClass.Expanded

    if (showPlayer && now.station != null && !expanded) {
        BackHandler { showPlayer = false }
        NowPlayingScreen(now, player, sideBySide = wide, onBack = { showPlayer = false })
        return
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Box(Modifier.weight(1f)) {
                when (Tab.entries[tab]) {
                    Tab.STATIONS -> StationsTab(now, player)
                    Tab.SEARCH -> SearchTab(now, player)
                    Tab.HISTORY -> HistoryTab()
                }
            }
            if (now.station != null && !expanded) MiniPlayer(now, player) { showPlayer = true }
        }
    }

    if (!wide) {
        Scaffold(bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t.ordinal, onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) })
                }
            }
        }) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
    } else {
        // Plegable abierto o tableta: raíl a la izquierda y, si cabe, el reproductor al lado.
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.padding(top = 24.dp)) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(selected = tab == t.ordinal, onClick = { tab = t.ordinal },
                            icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) })
                    }
                }
                content(Modifier.weight(1f).fillMaxSize())
                if (expanded && now.station != null) {
                    NowPlayingScreen(now, player, sideBySide = false, onBack = null,
                        modifier = Modifier.weight(1.1f))
                }
            }
        }
    }
}

// --- Piezas comunes ---------------------------------------------------------------------------

@Composable
fun Logo(url: String?, size: Int = 48) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp))) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (url != null) AsyncImage(model = url, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun StationRow(station: Station, current: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Logo(station.logoURL) },
        headlineContent = {
            Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.Bold else null,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = station.detail.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1) } },
        trailingContent = trailing,
    )
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun connectionText(c: Connection): String? = when (c) {
    Connection.CONNECTING -> stringResource(R.string.connecting)
    Connection.RECONNECTING -> stringResource(R.string.reconnecting)
    else -> null
}

@Composable
private fun MiniPlayer(now: NowState, player: PlayerController, onOpen: () -> Unit) {
    val station = now.station ?: return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(now.song?.artworkUrl ?: station.logoURL, 44)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(now.song?.track ?: station.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text(connectionText(now.connection) ?: now.song?.artist ?: station.name, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlayPauseButton(now, player)
        }
    }
}

@Composable
fun PlayPauseButton(now: NowState, player: PlayerController) {
    val busy = now.connection == Connection.CONNECTING || now.connection == Connection.RECONNECTING
    val playing = now.connection == Connection.PLAYING || busy
    IconButton(onClick = { player.toggle() }) {
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.pause else R.string.play))
    }
}

// --- Emisoras ---------------------------------------------------------------------------------

@Composable
private fun StationsTab(now: NowState, player: PlayerController) {
    val context = LocalContext.current
    val store = context.fm.stations
    val stations by store.stations.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Station?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        val n = text?.let { store.import(it) }
        Toast.makeText(context,
            if (n != null) context.getString(R.string.imported_n, n) else context.getString(R.string.import_failed),
            Toast.LENGTH_SHORT).show()
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(store.export().toByteArray()) }
        }.onSuccess { Toast.makeText(context, R.string.exported, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.tab_stations)) }, actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_stations)) }, onClick = {
                        menu = false; importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_stations)) }, onClick = {
                        menu = false; exporter.launch("emisoras.json")
                    })
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.add_station)) }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(stations, key = { it.id }) { s ->
                var rowMenu by remember { mutableStateOf(false) }
                StationRow(s, current = now.station?.streamURL == s.streamURL, onClick = { player.play(s) }) {
                    Box {
                        IconButton(onClick = { rowMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit_station)) },
                                onClick = { rowMenu = false; editing = s })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                                onClick = { rowMenu = false; store.remove(s.id) })
                        }
                    }
                }
            }
        }
    }

    if (adding) StationDialog(null, onDismiss = { adding = false }) { store.add(it); adding = false }
    editing?.let { s -> StationDialog(s, onDismiss = { editing = null }) { store.update(it); editing = null } }
}

@Composable
private fun StationDialog(initial: Station?, onDismiss: () -> Unit, onSave: (Station) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.streamURL.orEmpty()) }
    var logo by remember { mutableStateOf(initial?.logoURL.orEmpty()) }
    val validUrl = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_station else R.string.edit_station)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.stream_url)) }, singleLine = true,
                    isError = url.isNotBlank() && !validUrl,
                    supportingText = if (url.isNotBlank() && !validUrl) { { Text(stringResource(R.string.invalid_url)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(logo, { logo = it }, label = { Text(stringResource(R.string.logo_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && validUrl, onClick = {
                val base = initial ?: Station(name = "", streamURL = "")
                onSave(base.copy(name = name.trim(), streamURL = url.trim(), logoURL = logo.trim().ifEmpty { null }))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// --- Buscar en Radio Browser --------------------------------------------------------------------

@Composable
private fun SearchTab(now: NowState, player: PlayerController) {
    val context = LocalContext.current
    val store = context.fm.stations
    val mine by store.stations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Station>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf<String?>(null) }
    val country = Locale.getDefault().country.takeIf { it.length == 2 }

    fun run(block: suspend () -> List<Station>) {
        scope.launch {
            loading = true; failed = false
            results = try { block() } catch (e: NetworkException) { failed = true; null }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (results == null && query.isBlank()) { title = context.getString(R.string.popular_here); run { RadioBrowser.top(country) } }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_search)) })
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (query.isNotBlank()) { title = null; run { RadioBrowser.search(query.trim()) } }
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.size(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            failed -> Centered(stringResource(R.string.search_failed))
            results?.isEmpty() == true -> Centered(stringResource(R.string.search_empty))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                title?.let { t ->
                    item { Text(t, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp)) }
                }
                items(results.orEmpty(), key = { it.stationuuid ?: it.streamURL }) { s ->
                    val added = mine.any { it.streamURL == s.streamURL }
                    StationRow(s, current = now.station?.streamURL == s.streamURL, onClick = { player.play(s) }) {
                        IconButton(enabled = !added, onClick = {
                            if (store.add(s)) Toast.makeText(context, R.string.added, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, stringResource(R.string.add_to_my_stations))
                        }
                    }
                }
            }
        }
    }
}

// --- Historial ----------------------------------------------------------------------------------

private val timeOnly = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

private fun whenHeard(iso: String): String = runCatching {
    val t = LocalDateTime.parse(iso)
    if (t.toLocalDate() == LocalDate.now()) t.format(timeOnly) else t.format(dateTime)
}.getOrDefault("")

fun youtubeSearch(title: String, artist: String?): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" +
        Uri.encode(listOfNotNull(artist, title).joinToString(" "))))

@Composable
private fun HistoryTab() {
    val context = LocalContext.current
    val history = context.fm.history
    val songs by history.songs.collectAsStateWithLifecycle()
    val ignored by history.ignored.collectAsStateWithLifecycle()
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showIgnored by remember { mutableStateOf(false) }
    val shown = if (favoritesOnly) songs.filter { it.favorite } else songs

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_history)) }, actions = {
            IconButton(onClick = { showIgnored = true }) {
                Icon(Icons.Filled.Block, stringResource(R.string.ignored_titles))
            }
            IconButton(onClick = { confirmClear = true }, enabled = songs.any { !it.favorite }) {
                Icon(Icons.Filled.DeleteSweep, stringResource(R.string.clear_history))
            }
        })
        Row(Modifier.padding(horizontal = 16.dp)) {
            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly },
                label = { Text(stringResource(R.string.favorites_only)) },
                leadingIcon = { Icon(Icons.Filled.Favorite, null, Modifier.size(18.dp)) })
        }
        if (shown.isEmpty()) Centered(stringResource(R.string.history_empty))
        else LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { song -> SongRow(song) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(stringResource(R.string.clear_history_confirm)) },
        confirmButton = { TextButton(onClick = { history.clearNonFavorites(); confirmClear = false }) {
            Text(stringResource(R.string.clear_history)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )

    if (showIgnored) AlertDialog(
        onDismissRequest = { showIgnored = false },
        title = { Text(stringResource(R.string.ignored_titles)) },
        text = {
            if (ignored.isEmpty()) Text(stringResource(R.string.ignored_empty))
            else LazyColumn {
                items(ignored, key = { it.key }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.stationName) },
                        trailingContent = { TextButton(onClick = { history.unignore(entry) }) { Text(stringResource(R.string.restore)) } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { showIgnored = false }) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun SongRow(song: ListenedSong) {
    val context = LocalContext.current
    val history = context.fm.history
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { Logo(song.artworkUrl) },
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(song.artist, song.stationName, whenHeard(song.listenedAt).ifEmpty { null })
                .joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { history.toggleFavorite(song.id) }) {
                    Icon(if (song.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(R.string.favorite),
                        tint = if (song.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.search_youtube)) }, onClick = {
                            menu = false
                            runCatching { context.startActivity(youtubeSearch(song.title, song.artist)) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                            onClick = { menu = false; history.delete(song.id) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_forever)) },
                            onClick = { menu = false; history.ignoreAndPurge(song) })
                    }
                }
            }
        },
    )
}
