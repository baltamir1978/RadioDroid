@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package es.altamirano.fmdroid.ui

import es.altamirano.common.ui.onSecondaryClick
import es.altamirano.common.ui.Readable
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.abs

private enum class Screen { STATIONS, SEARCH, HISTORY }

/**
 * Como en RadioApp: una sola pantalla, «Mis Emisoras», con el historial, la búsqueda y
 * «añadir» arriba, y el minirreproductor abajo. El reproductor se abre encima.
 */
@Composable
fun FmScreen(player: PlayerController, width: WindowWidthSizeClass) {
    val app = LocalContext.current.fm
    val now by app.nowPlaying.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.STATIONS) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val wide = width != WindowWidthSizeClass.Compact
    val expanded = width == WindowWidthSizeClass.Expanded

    if (showPlayer && now.station != null && !expanded) {
        BackHandler { showPlayer = false }
        NowPlayingScreen(now, player, sideBySide = wide, onClose = { showPlayer = false })
        return
    }
    if (screen != Screen.STATIONS) BackHandler { screen = Screen.STATIONS }

    val main: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.STATIONS -> StationsScreen(now, player,
                        onSearch = { screen = Screen.SEARCH }, onHistory = { screen = Screen.HISTORY },
                        onPlayed = { if (!expanded) showPlayer = true })
                    Screen.SEARCH -> SearchScreen(now, player) { screen = Screen.STATIONS }
                    Screen.HISTORY -> HistoryScreen { screen = Screen.STATIONS }
                }
            }
            AnimatedVisibility(now.station != null && !expanded,
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                MiniPlayer(now, player) { showPlayer = true }
            }
        }
    }

    if (expanded) {
        // Tableta o plegable en horizontal: la lista y el reproductor a la vez.
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            main(Modifier.weight(1f).fillMaxSize())
            if (now.station != null) {
                NowPlayingScreen(now, player, sideBySide = false, onClose = null, modifier = Modifier.weight(1.1f))
            }
        }
    } else {
        main(Modifier.fillMaxSize())
    }
}

// --- Piezas comunes ---------------------------------------------------------------------------

@Composable
fun barColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    actionIconContentColor = MaterialTheme.colorScheme.primary,
    navigationIconContentColor = MaterialTheme.colorScheme.primary,
)

fun initials(name: String): String =
    name.split(" ").filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase()

/**
 * El logo de la emisora, como `StationLogo` de iOS: esquinas al 22 %, fondo blanco detrás (muchos
 * logos son transparentes) y, si no hay o no carga, sus iniciales sobre un color fijo por nombre.
 */
@Composable
fun StationLogo(name: String, url: String?, size: Dp, corner: Float = 0.22f) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * corner)), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Box(Modifier.fillMaxSize().background(LogoPalette[abs(name.hashCode()) % LogoPalette.size]),
                contentAlignment = Alignment.Center) {
                Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.38f).sp)
            }
        }
        if (url != null) AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            onSuccess = { loaded = true }, onError = { loaded = false },
            modifier = Modifier.fillMaxSize().background(if (loaded) Color.White else Color.Transparent),
        )
    }
}

/** Una carátula de canción (o la del historial), con el icono de radio si no la hay. */
@Composable
fun Artwork(url: String?, size: Dp, corner: Dp = 8.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.primary)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun reduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Las cuatro barras que se mueven junto a la emisora que suena. */
@Composable
private fun LiveIndicator(playing: Boolean) {
    val still = reduceMotion()
    val transition = rememberInfiniteTransition(label = "live")
    val label = stringResource(if (playing) R.string.live else R.string.paused)
    Row(Modifier.size(22.dp, 20.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(10, 16, 12, 8).forEachIndexed { i, max ->
            val base = 8f + i * 2
            val h = if (!playing) 5f else if (still) base else transition.animateFloat(
                base, max.toFloat(),
                infiniteRepeatable(tween(350 + i * 80), RepeatMode.Reverse), label = "bar$i",
            ).value
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun Centered(title: String, body: String? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun connectionText(c: Connection): String? = when (c) {
    Connection.CONNECTING -> stringResource(R.string.loading)
    Connection.RECONNECTING -> stringResource(R.string.reconnecting)
    else -> null
}

@Composable
private fun MiniPlayer(now: NowState, player: PlayerController, onOpen: () -> Unit) {
    val station = now.station ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                    StationLogo(station.name, station.logoURL, 42.dp)
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(now.song?.track ?: connectionText(now.connection) ?: stringResource(R.string.live),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PlayPauseButton(now, player)
            }
        }
    }
}

@Composable
fun PlayPauseButton(now: NowState, player: PlayerController) {
    val busy = now.connection == Connection.CONNECTING || now.connection == Connection.RECONNECTING
    val playing = now.connection == Connection.PLAYING || busy
    IconButton(onClick = { player.toggle() }, modifier = Modifier.size(44.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.pause else R.string.play), tint = MaterialTheme.colorScheme.primary)
    }
}

// --- Mis emisoras -----------------------------------------------------------------------------

@Composable
private fun StationsScreen(
    now: NowState, player: PlayerController,
    onSearch: () -> Unit, onHistory: () -> Unit, onPlayed: () -> Unit,
) {
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(colors = barColors(), title = { Text(stringResource(R.string.my_stations), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onHistory) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.tab_history)) }
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.tab_search)) }
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.add_station)) }
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
    ) { padding ->
        if (stations.isEmpty()) {
            Box(Modifier.padding(padding)) {
                Centered(stringResource(R.string.no_stations), stringResource(R.string.add_stations_hint))
            }
        } else Readable(Modifier.padding(padding).fillMaxSize()) { side -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = side, vertical = 8.dp)) {
            itemsIndexed(stations, key = { _, s -> s.id }) { i, s ->
                StationRow(s, active = now.station?.streamURL == s.streamURL,
                    playing = now.connection == Connection.PLAYING,
                    onTap = { player.play(s); onPlayed() },
                    onEdit = { editing = s },
                    onDelete = {
                        if (now.station?.id == s.id) player.stop()
                        store.remove(s.id)
                    })
                if (i < stations.lastIndex) HorizontalDivider(Modifier.padding(start = 82.dp),
                    color = MaterialTheme.colorScheme.outlineVariant)
            }
        } }
    }

    if (adding) StationDialog(null, onDismiss = { adding = false }) { store.add(it); adding = false }
    editing?.let { s -> StationDialog(s, onDismiss = { editing = null }) { store.update(it); editing = null } }
}

/** País en una cápsula y género al lado, como en iOS. */
@Composable
private fun StationDetail(station: Station) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        station.country?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
        station.genre?.let {
            Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StationRow(
    station: Station, active: Boolean, playing: Boolean,
    onTap: () -> Unit, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = if (onEdit != null) ({ menu = true }) else null)
                .then(if (onEdit != null) Modifier.onSecondaryClick { menu = true } else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.shadow(3.dp, RoundedCornerShape(12.dp))) { StationLogo(station.name, station.logoURL, 56.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(station.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                StationDetail(station)
            }
            if (active) LiveIndicator(playing)
            trailing?.invoke()
        }
        // Pulsación larga: el menú contextual de iOS.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { menu = false; onTap() })
            onEdit?.let { DropdownMenuItem(text = { Text(stringResource(R.string.edit_station)) }, onClick = { menu = false; it() }) }
            onDelete?.let {
                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; it() })
            }
        }
    }
}

@Composable
private fun StationDialog(initial: Station?, onDismiss: () -> Unit, onSave: (Station) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.streamURL.orEmpty()) }
    var logo by remember { mutableStateOf(initial?.logoURL.orEmpty()) }
    val validUrl = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
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
private fun SearchScreen(now: NowState, player: PlayerController, onBack: () -> Unit) {
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
        TopAppBar(colors = barColors(), title = { Text(stringResource(R.string.tab_search), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (query.isNotBlank()) { title = null; run { RadioBrowser.search(query.trim()) } }
            }),
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            failed -> Centered(stringResource(R.string.search_failed))
            results?.isEmpty() == true -> Centered(stringResource(R.string.search_empty))
            else -> Readable(Modifier.fillMaxSize()) { side -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = side)) {
                title?.let { t ->
                    item { Text(t, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp)) }
                }
                items(results.orEmpty(), key = { it.stationuuid ?: it.streamURL }) { s ->
                    val added = mine.any { it.streamURL == s.streamURL }
                    StationRow(s, active = now.station?.streamURL == s.streamURL,
                        playing = now.connection == Connection.PLAYING, onTap = { player.play(s) }) {
                        IconButton(enabled = !added, onClick = {
                            if (store.add(s)) Toast.makeText(context, R.string.added, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, stringResource(R.string.add_to_my_stations),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 82.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            } }
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
private fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val history = context.fm.history
    val songs by history.songs.collectAsStateWithLifecycle()
    val ignored by history.ignored.collectAsStateWithLifecycle()
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showIgnored by remember { mutableStateOf(false) }
    val shown = if (favoritesOnly) songs.filter { it.favorite } else songs

    Column(Modifier.fillMaxSize()) {
        TopAppBar(colors = barColors(), title = { Text(stringResource(R.string.tab_history), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { showIgnored = true }) { Icon(Icons.Filled.Block, stringResource(R.string.ignored_titles)) }
                IconButton(onClick = { confirmClear = true }, enabled = songs.any { !it.favorite }) {
                    Icon(Icons.Filled.DeleteSweep, stringResource(R.string.clear_history))
                }
            })
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly },
                label = { Text(stringResource(R.string.favorites_only)) },
                leadingIcon = { Icon(Icons.Filled.Favorite, null, Modifier.size(18.dp)) })
        }
        if (shown.isEmpty()) Centered(stringResource(R.string.history_empty))
        else Readable(Modifier.fillMaxSize()) { side -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = side)) {
            items(shown, key = { it.id }) { song ->
                SongRow(song)
                HorizontalDivider(Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        } }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = MaterialTheme.colorScheme.background,
        text = { Text(stringResource(R.string.clear_history_confirm)) },
        confirmButton = { TextButton(onClick = { history.clearNonFavorites(); confirmClear = false }) {
            Text(stringResource(R.string.clear_history)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )

    if (showIgnored) AlertDialog(
        onDismissRequest = { showIgnored = false },
        containerColor = MaterialTheme.colorScheme.background,
        title = { Text(stringResource(R.string.ignored_titles)) },
        text = {
            if (ignored.isEmpty()) Text(stringResource(R.string.ignored_empty))
            else LazyColumn {
                items(ignored, key = { it.key }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.stationName) },
                        trailingContent = { TextButton(onClick = { history.unignore(entry) }) { Text(stringResource(R.string.restore)) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Artwork(song.artworkUrl, 48.dp) },
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
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
