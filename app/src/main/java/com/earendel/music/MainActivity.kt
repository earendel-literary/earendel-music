package com.earendel.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Track(val id: String, val title: String, val artist: String, val stream: String)
data class UserPlaylist(val name: String, val tracks: List<Track>)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { EarendelMusic(player) }
    }
    override fun onStop() { super.onStop() }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

private val ink = Color(0xFF090812)
private val panel = Color(0xFF191526)
private val panel2 = Color(0xFF282039)
private val violet = Color(0xFFAA8CFF)
private val muted = Color(0xFFAAA4B9)

@Composable
fun EarendelMusic(player: ExoPlayer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("earendel_music", 0) }
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var current by remember { mutableStateOf<Track?>(null) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search for music you can legally stream.") }
    var tab by remember { mutableStateOf("Home") }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var addTrackTarget by remember { mutableStateOf<Track?>(null) }
    val scope = rememberCoroutineScope()
    val favorites = remember { mutableStateListOf<Track>().apply { addAll(readTracks(prefs, "liked")) } }
    val playlists = remember { mutableStateListOf<UserPlaylist>().apply { addAll(readPlaylists(prefs)) } }

    fun persist() {
        prefs.edit().putString("liked", tracksJson(favorites)).putString("playlists", playlistsJson(playlists)).apply()
    }
    fun search(term: String) {
        if (term.isBlank()) return
        loading = true
        message = "Searching licensed audio…"
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { searchArchive(term) }
                tracks = found
                message = if (found.isEmpty()) "No matching Creative Commons audio found. Try another search." else "${found.size} tracks found"
            } catch (_: Exception) {
                message = "Could not load music. Check your internet connection and try again."
            } finally { loading = false }
        }
    }
    fun play(track: Track, list: List<Track> = tracks) {
        current = track
        val queue = (list.ifEmpty { listOf(track) }).map { MediaItem.fromUri(it.stream) }
        player.setMediaItems(queue, (list.ifEmpty { listOf(track) }).indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0L)
        player.prepare()
        player.play()
        playing = true
    }
    fun toggleLike(track: Track) {
        if (favorites.any { it.id == track.id }) favorites.removeAll { it.id == track.id } else favorites.add(track)
        persist()
    }

    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            duration = player.duration.coerceAtLeast(0L).toFloat()
            position = player.currentPosition.coerceAtLeast(0L).toFloat()
            delay(500)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = ink, surface = panel, primary = violet, onBackground = Color.White, onSurface = Color.White)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241A38), ink, ink)))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(26.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("EARENDEL", color = violet, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text("MUSIC", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    }
                    Icon(Icons.Default.GraphicEq, null, tint = violet, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(18.dp))

                when (tab) {
                    "Search" -> {
                        Text("Search", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        SearchBox(query, { query = it }, { search(query) })
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = muted, fontSize = 12.sp)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
                        TrackList(tracks, favorites, onPlay = { play(it, tracks) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                    }
                    "Library" -> {
                        Text("Your Library", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LibraryPill("Playlists", true) { selectedPlaylist = null }
                            LibraryPill("Liked Songs", false) { selectedPlaylist = "♥ Liked Songs" }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (selectedPlaylist == "♥ Liked Songs") {
                            Text("LIKED SONGS", color = violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            TrackList(favorites, favorites, onPlay = { play(it, favorites) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                        } else if (selectedPlaylist != null) {
                            val pl = playlists.firstOrNull { it.name == selectedPlaylist }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                                Text(selectedPlaylist ?: "", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            TrackList(pl?.tracks ?: emptyList(), favorites, onPlay = { play(it, pl?.tracks ?: emptyList()) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("YOUR PLAYLISTS", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, null, tint = violet); Text("Create", color = violet) }
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                item {
                                    LibraryRow("Liked Songs", "${favorites.size} liked tracks", "♥", violet) { selectedPlaylist = "♥ Liked Songs" }
                                }
                                items(playlists, key = { it.name }) { pl ->
                                    LibraryRow(pl.name, "${pl.tracks.size} songs", "♫", Color(0xFF8E77C8)) { selectedPlaylist = pl.name }
                                }
                                if (playlists.isEmpty()) item { Text("Your playlists will appear here. Create one to get started.", color = muted, fontSize = 13.sp) }
                            }
                        }
                    }
                    else -> {
                        Text("Good evening", color = muted, fontSize = 13.sp)
                        Text("Find your next favourite.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        SearchBox(query, { query = it }, { tab = "Search"; search(query) })
                        Spacer(Modifier.height(20.dp))
                        Text("MADE FOR YOU", color = muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            listOf("ASHES", "Artcell", "Highway", "Shonar Bangla Circus").forEach { name ->
                                Surface(color = panel2, shape = RoundedCornerShape(18.dp), modifier = Modifier.clickable { query = name; tab = "Search"; search(name) }) {
                                    Text(name, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DISCOVER MUSIC", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { tab = "Search" }) { Text("Search all", color = violet) }
                        }
                        Text(message, color = muted, fontSize = 12.sp)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
                        TrackList(tracks, favorites, onPlay = { play(it, tracks) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                    }
                }

                current?.let { track ->
                    Column(Modifier.fillMaxWidth().background(Color(0xFF302344), RoundedCornerShape(topStart = 15.dp, topEnd = 15.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 13.sp)
                                Text(track.artist, color = muted, fontSize = 11.sp, maxLines = 1)
                            }
                            IconButton(onClick = { toggleLike(track) }) { Icon(Icons.Default.Favorite, null, tint = if (favorites.any { it.id == track.id }) violet else muted) }
                            IconButton(onClick = { player.seekToPrevious(); playing = true }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
                            IconButton(onClick = { player.seekToNext(); playing = true }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle }) { Text(if (shuffle) "SHUFFLE ON" else "SHUFFLE", color = if (shuffle) violet else muted, fontSize = 9.sp) }
                            TextButton(onClick = { repeat = !repeat; player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }) { Text(if (repeat) "REPEAT ALL" else "REPEAT OFF", color = if (repeat) violet else muted, fontSize = 9.sp) }
                        }
                        Slider(value = if (duration > 0) position.coerceIn(0f, duration) else 0f, onValueChange = { position = it }, onValueChangeFinished = { player.seekTo(position.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1f), modifier = Modifier.height(22.dp), colors = SliderDefaults.colors(thumbColor = violet, activeTrackColor = violet, inactiveTrackColor = muted))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(timeLabel(position), color = muted, fontSize = 9.sp)
                            Text(timeLabel(duration), color = muted, fontSize = 9.sp)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().background(ink).padding(top = 7.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    BottomItem("Home", Icons.Default.Home, tab == "Home") { tab = "Home" }
                    BottomItem("Search", Icons.Default.Search, tab == "Search") { tab = "Search" }
                    BottomItem("Library", Icons.Default.LibraryMusic, tab == "Library") { tab = "Library"; selectedPlaylist = null }
                }
            }
        }

        if (showCreate) AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create playlist", color = Color.White) },
            text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Playlist name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val name = newPlaylistName.trim()
                if (name.isNotEmpty() && playlists.none { it.name.equals(name, true) }) {
                    playlists.add(UserPlaylist(name, emptyList()))
                    persist()
                }
                newPlaylistName = ""; showCreate = false
            }) { Text("Create", color = violet) } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
            containerColor = panel
        )
        addTrackTarget?.let { track ->
            AlertDialog(
                onDismissRequest = { addTrackTarget = null },
                title = { Text("Add to playlist", color = Color.White) },
                text = {
                    Column {
                        if (playlists.isEmpty()) Text("Create a playlist in Your Library first.", color = muted)
                        playlists.forEach { pl ->
                            TextButton(onClick = {
                                val index = playlists.indexOfFirst { it.name == pl.name }
                                if (index >= 0 && playlists[index].tracks.none { it.id == track.id }) {
                                    playlists[index] = playlists[index].copy(tracks = playlists[index].tracks + track)
                                    persist()
                                }
                                addTrackTarget = null
                            }) { Text(pl.name, color = Color.White) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { addTrackTarget = null }) { Text("Close", color = violet) } },
                containerColor = panel
            )
        }
    }
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(horizontal = 13.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, tint = violet)
        Spacer(Modifier.width(9.dp))
        OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.weight(1f), placeholder = { Text("Songs, artists, albums…", color = muted, fontSize = 13.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        TextButton(onClick = onSearch) { Text("GO", color = violet, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun TrackList(itemsList: List<Track>, favorites: List<Track>, onPlay: (Track) -> Unit, onLike: (Track) -> Unit, onAdd: (Track) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 390.dp), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items(itemsList, key = { it.id }) { track ->
            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).clickable { onPlay(track) }.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Color(0xFF35264D), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = violet) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 13.sp)
                    Text(track.artist, color = muted, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = { onLike(track) }) { Icon(Icons.Default.Favorite, null, tint = if (favorites.any { it.id == track.id }) violet else muted) }
                IconButton(onClick = { onAdd(track) }) { Icon(Icons.Default.PlaylistAdd, null, tint = muted) }
            }
        }
        if (itemsList.isEmpty()) item { Text("No tracks yet. Search for music to get started.", color = muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
    }
}

@Composable
private fun LibraryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) violet.copy(alpha = .2f) else panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { onClick() }) {
        Text(label, color = if (selected) violet else Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}
@Composable
private fun LibraryRow(title: String, subtitle: String, symbol: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(tint.copy(alpha = .22f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(symbol, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(subtitle, color = muted, fontSize = 11.sp) }
    }
}
@Composable
private fun BottomItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }.padding(horizontal = 18.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (selected) violet else muted, modifier = Modifier.size(22.dp))
        Text(label, color = if (selected) violet else muted, fontSize = 10.sp)
    }
}
private fun timeLabel(ms: Float): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
private fun trackJson(track: Track) = JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist).put("stream", track.stream)
private fun tracksJson(list: List<Track>) = JSONArray().also { arr -> list.forEach { arr.put(trackJson(it)) } }.toString()
private fun parseTracks(raw: String?): List<Track> = try {
    val arr = JSONArray(raw ?: "[]")
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Track(it.optString("id"), it.optString("title"), it.optString("artist"), it.optString("stream")) } }
} catch (_: Exception) { emptyList() }
private fun readTracks(prefs: android.content.SharedPreferences, key: String) = parseTracks(prefs.getString(key, "[]"))
private fun playlistsJson(list: List<UserPlaylist>) = JSONArray().also { arr -> list.forEach { arr.put(JSONObject().put("name", it.name).put("tracks", JSONArray(tracksJson(it.tracks)))) } }.toString()
private fun readPlaylists(prefs: android.content.SharedPreferences): List<UserPlaylist> = try {
    val arr = JSONArray(prefs.getString("playlists", "[]"))
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { UserPlaylist(it.optString("name"), parseTracks(it.optJSONArray("tracks")?.toString())) } }
} catch (_: Exception) { emptyList() }

private fun searchArchive(term: String): List<Track> {
    val client = OkHttpClient()
    val encoded = URLEncoder.encode(term, "UTF-8")
    val url = "https://archive.org/advancedsearch.php?q=mediatype%3Aaudio+AND+licenseurl%3A%2Acreativecommons.org%2A+AND+title%3A%28$encoded%29&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=20&page=1&output=json"
    val req = Request.Builder().url(url).header("User-Agent", "EARENDEL-MUSIC/1.0").build()
    val body = client.newCall(req).execute().use { it.body?.string() ?: error("Empty response") }
    val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
    val results = mutableListOf<Track>()
    for (i in 0 until docs.length()) {
        val doc = docs.getJSONObject(i)
        val id = doc.optString("identifier")
        if (id.isBlank()) continue
        try {
            val metaReq = Request.Builder().url("https://archive.org/metadata/$id").build()
            val metaBody = client.newCall(metaReq).execute().use { it.body?.string() ?: "" }
            val files = JSONObject(metaBody).optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val name = f.optString("name")
                if (name.endsWith(".mp3", true) || name.endsWith(".ogg", true) || name.endsWith(".m4a", true)) {
                    val title = doc.optString("title", id).ifBlank { id }
                    val artist = doc.optString("creator", "Internet Archive").ifBlank { "Internet Archive" }
                    results.add(Track("$id-$name", title, artist, "https://archive.org/download/$id/${URLEncoder.encode(name, "UTF-8")}"))
                    break
                }
            }
        } catch (_: Exception) { }
    }
    return results
}
