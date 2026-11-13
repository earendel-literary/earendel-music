package com.earendel.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
    var homeCollection by remember { mutableStateOf<String?>(null) }
    var isAuthenticated by remember { mutableStateOf(prefs.getBoolean("authenticated", false)) }
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
                val found = withContext(Dispatchers.IO) { searchOnline(term) }
                tracks = found
                val previews = found.count { it.id.startsWith("apple-") }
                val licensed = found.size - previews
                message = if (found.isEmpty()) "No online matches found. Try another spelling or search." else
                    "$previews songs from the online music catalogue (30-second previews) • $licensed licensed full-track files where available"
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

    if (!isAuthenticated) {
        AuthGate(onAuthAttempt = { message = "Authentication needs EARENDEL Firebase project configuration. Add its web API key and Google OAuth client ID to enable secure sign-in." })
    } else MaterialTheme(colorScheme = darkColorScheme(background = ink, surface = panel, primary = violet, onBackground = Color.White, onSurface = Color.White)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241A38), ink, ink)))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("EARENDEL", color = violet, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text("MUSIC", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    }
                    Icon(Icons.Default.GraphicEq, null, tint = violet, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(18.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    "Search" -> {
                        Column(Modifier.fillMaxSize()) {
                        Text("Search", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        SearchBox(query, { query = it }, { search(query) })
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = muted, fontSize = 12.sp)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
                        TrackList(tracks, favorites, onPlay = { play(it, tracks) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                        }
                    }
                    "Library" -> {
                        Column(Modifier.fillMaxSize()) {
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
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
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
                    }
                    else -> {
                        if (homeCollection == "Bangla Rock") {
                            BanglaRockScreen(
                                modifier = Modifier.fillMaxSize(),
                                tracks = tracks,
                                favorites = favorites,
                                loading = loading,
                                message = message,
                                onBack = { homeCollection = null },
                                onPlay = { play(it, tracks) },
                                onLike = { toggleLike(it) },
                                onAdd = { addTrackTarget = it },
                                onArtist = { name -> query = name; search(name) }
                            )
                        } else HomeScreen(
                            modifier = Modifier.fillMaxSize(),
                            tracks = tracks,
                            favorites = favorites,
                            message = message,
                            loading = loading,
                            onSearch = { tab = "Search" },
                            onBanglaRock = { homeCollection = "Bangla Rock"; query = "Bangla rock"; search("Bangla rock") },
                            onArtist = { name -> query = name; tab = "Search"; search(name) },
                            onPlay = { play(it, tracks) },
                            onLike = { toggleLike(it) },
                            onAdd = { addTrackTarget = it }
                        )
                    }
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
                    BottomItem("Home", Icons.Default.Home, tab == "Home") { tab = "Home"; homeCollection = null }
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
private fun AuthGate(onAuthAttempt: () -> Unit) {
    var page by remember { mutableStateOf("welcome") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.align(Alignment.TopEnd).size(230.dp).background(Brush.radialGradient(listOf(Color(0x553F168C), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomStart).size(260.dp).background(Brush.radialGradient(listOf(Color(0x553F168C), Color.Transparent))))
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(126.dp).background(Brush.radialGradient(listOf(Color(0xFF8E46FF), Color(0xFF29134A), Color.Black)), RoundedCornerShape(100)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(62.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("E A R E N D E L", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            Text("M U S I C  ✦", color = violet, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(28.dp))
            if (page == "welcome") {
                Text("Millions of songs.", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Free on EARENDEL.", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(30.dp))
                Button(onClick = { page = "signup"; notice = "" }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF853CFF))) { Text("Sign up free", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { page = "login"; notice = "" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Log in", color = Color.White) }
            } else {
                Text(if (page == "signup") "Create account" else "Welcome back", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (page == "signup") "Join EARENDEL and enjoy music" else "Log in to continue", color = muted, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gmail or email address", color = Color.White) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = violet, focusedLabelColor = violet, unfocusedLabelColor = Color.White, focusedBorderColor = violet, unfocusedBorderColor = Color(0xFF777080), focusedContainerColor = Color(0xFF17131F), unfocusedContainerColor = Color(0xFF17131F)))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password", color = Color.White) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = violet, focusedLabelColor = violet, unfocusedLabelColor = Color.White, focusedBorderColor = violet, unfocusedBorderColor = Color(0xFF777080), focusedContainerColor = Color(0xFF17131F), unfocusedContainerColor = Color(0xFF17131F)))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { notice = "Secure email authentication is not connected yet. Configure Firebase Authentication to create or access accounts." ; onAuthAttempt() }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF853CFF))) {
                    Text(if (page == "signup") "Create account" else "Log in")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { notice = "Google sign-in requires the EARENDEL Google OAuth client ID and Firebase configuration."; onAuthAttempt() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("G  Continue with Google", color = Color.White)
                }
                TextButton(onClick = { page = if (page == "signup") "login" else "signup"; notice = "" }) {
                    Text(if (page == "signup") "Already have an account? Log in" else "New to EARENDEL? Sign up free", color = violet)
                }
                if (notice.isNotBlank()) Text(notice, color = Color(0xFFFFC078), fontSize = 11.sp)
            }
            Spacer(Modifier.height(18.dp))
            if (page != "welcome") TextButton(onClick = { page = "welcome"; notice = "" }) { Text("Back", color = muted) }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    tracks: List<Track>,
    favorites: List<Track>,
    message: String,
    loading: Boolean,
    onSearch: () -> Unit,
    onBanglaRock: () -> Unit,
    onArtist: (String) -> Unit,
    onPlay: (Track) -> Unit,
    onLike: (Track) -> Unit,
    onAdd: (Track) -> Unit
) {
    val artists = listOf("BANGLA ROCK", "BANGLA INDIE", "ALTERNATIVE", "ACOUSTIC")
    Column(modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GOOD EVENING", color = violet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("What sounds good?", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            }
            Surface(color = panel2, shape = RoundedCornerShape(50)) {
                Icon(Icons.Default.NotificationsNone, null, tint = Color.White, modifier = Modifier.padding(11.dp).size(21.dp))
            }
        }
        Spacer(Modifier.height(17.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(184.dp),
            shape = RoundedCornerShape(25.dp),
            color = Color(0xFF352052)
        ) {
            Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF7547A9), Color(0xFF352052), Color(0xFF171323)))).padding(19.dp)) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text("EARENDEL EXCLUSIVE", color = Color(0xFFE4D5FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Your world.\nYour music.", color = Color.White, fontSize = 27.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Color.White, shape = RoundedCornerShape(30.dp), modifier = Modifier.clickable { onSearch() }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Icon(Icons.Default.Explore, null, tint = Color(0xFF392052), modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Explore music", color = Color(0xFF392052), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Box(Modifier.align(Alignment.CenterEnd).size(112.dp).background(Color(0x557F62B5), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFD8C4FF), modifier = Modifier.size(76.dp))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JUMP BACK IN", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Your picks", color = muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HomeQuickCard("Liked Songs", "Your favourites", "♥", Color(0xFF7446A6), Modifier.weight(1f)) { onArtist("Bangla alternative rock") }
            HomeQuickCard("Bangla Rock", "Open songs & artists", "♫", Color(0xFF315C70), Modifier.weight(1f), onBanglaRock)
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HomeQuickCard("Indie Vibes", "Discover something new", "✦", Color(0xFF79513D), Modifier.weight(1f)) { onArtist("Bangla indie") }
            HomeQuickCard("New Discoveries", "Find your next song", "◉", Color(0xFF465B48), Modifier.weight(1f)) { onSearch() }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MADE FOR YOU", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("CURATED", color = violet, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            artists.forEachIndexed { index, name ->
                Column(Modifier.width(104.dp).clickable { onArtist(name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(88.dp).background(
                        Brush.linearGradient(listOf(
                            listOf(Color(0xFF9A5C9D), Color(0xFF46306D)),
                            listOf(Color(0xFF476C82), Color(0xFF1D3048)),
                            listOf(Color(0xFFB06B4D), Color(0xFF4C2C37)),
                            listOf(Color(0xFF9A8052), Color(0xFF39304B))
                        )[index]), RoundedCornerShape(23.dp)), contentAlignment = Alignment.Center) {
                        Text(name.take(2), color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(name, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("Explore music", color = muted, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(21.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TRENDING BANGLA", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSearch) { Text("See all", color = violet, fontSize = 11.sp) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
        if (tracks.isNotEmpty()) {
            TrackList(tracks.take(5), favorites, onPlay, onLike, onAdd)
        } else {
            Text("Discover Bangla rock, indie and alternative sounds.", color = muted, fontSize = 11.sp)
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                HomeGenreTile("Alternative", "ALT", Color(0xFF69477F), Modifier.weight(1f)) { onArtist("Bangla alternative") }
                HomeGenreTile("Acoustic", "AC", Color(0xFF456A68), Modifier.weight(1f)) { onArtist("Bangla acoustic") }
                HomeGenreTile("Rock", "RK", Color(0xFF8A5946), Modifier.weight(1f)) { onArtist("Bangla rock") }
            }
            Text(message, color = muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BANGLA ALBUMS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("PLAY ONLINE", color = violet, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAlbumTile("Bangla Rock\nAnthems", "BANGLA ROCK", Color(0xFF604078), Modifier.weight(1f)) { onArtist("Bangla rock songs") }
            HomeAlbumTile("Bangla Indie\nDiscoveries", "BANGLA INDIE", Color(0xFF3F6661), Modifier.weight(1f)) { onArtist("Bangla indie songs") }
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAlbumTile("Modern Bangla\nMelodies", "MODERN", Color(0xFF755044), Modifier.weight(1f)) { onArtist("modern Bangla songs") }
            HomeAlbumTile("Acoustic Bangla\nSessions", "ACOUSTIC", Color(0xFF465B7C), Modifier.weight(1f)) { onArtist("Bangla acoustic songs") }
        }
        Text("Tap an album to search its songs online. Available tracks depend on the music catalogue.", color = muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(18.dp))
        Text("YOUR COLLECTION", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            HomeAlbumTile("Midnight\nMelodies", "MOOD MIX", Color(0xFF4E477F), Modifier.weight(1f)) { onArtist("midnight mellow music") }
            HomeAlbumTile("Bangla\nUnderground", "LOCAL SOUND", Color(0xFF794A4D), Modifier.weight(1f)) { onArtist("Bangla underground") }
        }
        Spacer(Modifier.height(8.dp))
        Text("MORE TO EXPLORE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HomeGenreTile("Bangla Indie", "INDIE", Color(0xFF526C63), Modifier.weight(1f)) { onArtist("Bangla indie") }
            HomeGenreTile("Hindi Hits", "HINDI", Color(0xFF77523D), Modifier.weight(1f)) { onArtist("Hindi songs") }
            HomeGenreTile("English Pop", "POP", Color(0xFF4E5480), Modifier.weight(1f)) { onArtist("English pop") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HomeGenreTile("Acoustic", "SOFT", Color(0xFF53617B), Modifier.weight(1f)) { onArtist("acoustic songs") }
            HomeGenreTile("Rock Classics", "ROCK", Color(0xFF785044), Modifier.weight(1f)) { onArtist("rock classics") }
            HomeGenreTile("Chill Mix", "CHILL", Color(0xFF4E6B70), Modifier.weight(1f)) { onArtist("chill music") }
        }
        Spacer(Modifier.height(18.dp))
        Text("BANGLA ARTISTS TO EXPLORE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        listOf("Bangla Alternative", "Bangla Indie", "Modern Bangla", "Acoustic Bangla").forEach { genre ->
            LibraryRow(genre, "Discover tracks online", "♫", Color(0xFF8E77C8)) { onArtist(genre) }
            Spacer(Modifier.height(7.dp))
        }
        Spacer(Modifier.height(18.dp))
    }
}


@Composable
private fun BanglaRockScreen(
    modifier: Modifier,
    tracks: List<Track>,
    favorites: List<Track>,
    loading: Boolean,
    message: String,
    onBack: () -> Unit,
    onPlay: (Track) -> Unit,
    onLike: (Track) -> Unit,
    onAdd: (Track) -> Unit,
    onArtist: (String) -> Unit
) {
    Column(modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("BANGLA ROCK", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Bangla bands & alternative sounds", color = muted, fontSize = 11.sp)
            }
            Icon(Icons.Default.LibraryMusic, null, tint = violet, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = Color(0xFF352052), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("BANGLA ROCK COLLECTION", color = Color(0xFFE1D2FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(6.dp))
                Text("Guitar, poetry & emotion.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("Search online for tracks by your favourite Bangla bands.", color = muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(13.dp))
        Text("BANDS", color = violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Bangla Rock", "Alternative", "Indie", "Acoustic").forEach { genre ->
                AssistChip(onClick = { onArtist(genre) }, label = { Text(genre) }, colors = AssistChipDefaults.assistChipColors(labelColor = Color.White, containerColor = panel2))
            }
        }
        Spacer(Modifier.height(13.dp))
        Text("ONLINE SONG RESULTS", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(message, color = muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 6.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
        TrackList(tracks, favorites, onPlay, onLike, onAdd)
    }
}

@Composable
private fun HomeQuickCard(title: String, subtitle: String, symbol: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.background(panel, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(43.dp).background(tint, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Text(symbol, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, color = muted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun HomeGenreTile(title: String, short: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(tint, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(12.dp)) {
        Text(short, color = Color.White.copy(alpha = .65f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(15.dp))
        Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeAlbumTile(title: String, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(Brush.linearGradient(listOf(tint, panel)), RoundedCornerShape(17.dp)).clickable { onClick() }.padding(13.dp)) {
        Box(Modifier.fillMaxWidth().height(83.dp).background(Color.White.copy(alpha = .08f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Album, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(43.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(label, color = Color(0xFFD9C8FF), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
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

private fun searchOnline(term: String): List<Track> {
    val apple = try { searchAppleCatalogue(term) } catch (_: Exception) { emptyList() }
    val archive = try { searchArchive(term) } catch (_: Exception) { emptyList() }
    val seen = mutableSetOf<String>()
    return (apple + archive).filter { track ->
        val key = (track.title.trim().lowercase() + "|" + track.artist.trim().lowercase())
        seen.add(key)
    }
}

private fun searchAppleCatalogue(term: String): List<Track> {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val encoded = URLEncoder.encode(term, "UTF-8")
    val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=30&country=bd"
    val req = Request.Builder().url(url).header("User-Agent", "EARENDEL-MUSIC/1.1").build()
    val body = client.newCall(req).execute().use { it.body?.string() ?: error("Empty catalogue response") }
    val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
    return (0 until results.length()).mapNotNull { i ->
        val item = results.optJSONObject(i) ?: return@mapNotNull null
        val preview = item.optString("previewUrl")
        val title = item.optString("trackName")
        val artist = item.optString("artistName", "Unknown artist")
        if (preview.isBlank() || title.isBlank()) null
        else Track("apple-" + item.optString("trackId", i.toString()), title, artist, preview)
    }
}

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
