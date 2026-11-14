package com.earendel.music

import android.os.Bundle
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
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
import java.io.File
import java.io.FileOutputStream

data class Track(val id: String, val title: String, val artist: String, val stream: String, val artworkUrl: String = "")
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
    var playbackQueue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search free, openly licensed full-length music.") }
    var tab by remember { mutableStateOf("Home") }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var addTrackTarget by remember { mutableStateOf<Track?>(null) }
    var homeCollection by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var isAuthenticated by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val favorites = remember { mutableStateListOf<Track>().apply { addAll(readTracks(prefs, "liked")) } }
    val playlists = remember { mutableStateListOf<UserPlaylist>().apply { addAll(readPlaylists(prefs)) } }
    val localSongs = remember { mutableStateListOf<Track>().apply { addAll(readTracks(prefs, "local_songs")) } }
    fun persist() {
        prefs.edit().putString("liked", tracksJson(favorites)).putString("playlists", playlistsJson(playlists)).putString("local_songs", tracksJson(localSongs)).apply()
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            }
            scope.launch {
                val imported = withContext(Dispatchers.IO) { uris.mapNotNull { uri -> runCatching { localTrackFromUri(context, uri) }.getOrNull() } }
                imported.forEach { track -> if (localSongs.none { it.id == track.id }) localSongs.add(track) }
                persist()
                Toast.makeText(context, if (imported.isEmpty()) "Could not import selected audio files." else "${imported.size} song(s) added to My Songs.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun search(term: String, albumOnly: Boolean = false) {
        if (term.isBlank()) return
        loading = true
        message = "Searching free full-length music…"
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { if (albumOnly) searchArchive(term, 60) else searchOnline(term) }
                tracks = found
                message = if (found.isEmpty()) "No verified free full-length tracks found. Try another spelling or search." else
                    "\${found.size} free, openly licensed full-length tracks found. Availability depends on the archive catalogue."
            } catch (_: Exception) {
                message = "Could not load music. Check your internet connection and try again."
            } finally { loading = false }
        }
    }
    fun play(track: Track, list: List<Track> = tracks) {
        val selectedQueue = (list.ifEmpty { listOf(track) })
        playbackQueue = selectedQueue
        current = track
        val queue = selectedQueue.map { MediaItem.fromUri(it.stream) }
        player.setMediaItems(queue, selectedQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0L)
        player.prepare()
        player.play()
        playing = true
        showNowPlaying = true
    }
    fun toggleLike(track: Track) {
        if (favorites.any { it.id == track.id }) favorites.removeAll { it.id == track.id } else favorites.add(track)
        persist()
    }

    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            val queueIndex = player.currentMediaItemIndex
            if (queueIndex in playbackQueue.indices) current = playbackQueue[queueIndex]
            duration = player.duration.coerceAtLeast(0L).toFloat()
            position = player.currentPosition.coerceAtLeast(0L).toFloat()
            delay(500)
        }
    }

    LaunchedEffect(isAuthenticated) { if (isAuthenticated && tracks.isEmpty() && !loading) search("Bangla music") }

    MaterialTheme(colorScheme = darkColorScheme(background = ink, surface = panel, primary = violet, onBackground = Color.White, onSurface = Color.White)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241A38), ink, ink)))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("EARENDEL", color = violet, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text("MUSIC", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    }
                    val logoPulse = rememberInfiniteTransition(label = "logoPulse").animateFloat(
                        initialValue = 0.92f, targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                        label = "logoScale"
                    )
                    Icon(Icons.Default.GraphicEq, null, tint = violet, modifier = Modifier.size(32.dp).graphicsLayer {
                        scaleX = logoPulse.value; scaleY = logoPulse.value
                    })
                }
                Spacer(Modifier.height(18.dp))

                Box(Modifier.weight(1f).fillMaxWidth().animateContentSize(tween(350, easing = FastOutSlowInEasing))) {
                when (tab) {
                    "Search" -> {
                        Column(Modifier.fillMaxSize()) {
                        Text("Search", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        SearchBox(query, { query = it }, { search(query) })
                        Spacer(Modifier.height(8.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(message, color = muted, fontSize = 12.sp)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
                        TrackList(tracks, favorites, onPlay = { play(it, tracks) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it }, modifier = Modifier.weight(1f))
                        }
                    }
                    "Library" -> {
                        Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Your Library", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF392752), contentColor = violet)) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Add from phone", fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LibraryPill("Playlists", true) { selectedPlaylist = null }
                            LibraryPill("Liked Songs", false) { selectedPlaylist = "♥ Liked Songs" }
                            LibraryPill("My Songs", false) { selectedPlaylist = "♪ My Songs" }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (selectedPlaylist == "♥ Liked Songs") {
                            Text("LIKED SONGS", color = violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            TrackList(favorites, favorites, onPlay = { play(it, favorites) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                        } else if (selectedPlaylist == "♪ My Songs") {
                            Text("MY IMPORTED SONGS · ${localSongs.size}", color = violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            TrackList(localSongs, favorites, onPlay = { play(it, localSongs) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                        } else if (selectedPlaylist != null) {
                            val pl = playlists.firstOrNull { it.name == selectedPlaylist }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                                Text(selectedPlaylist ?: "", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            TrackList(pl?.tracks ?: emptyList(), favorites, onPlay = { play(it, pl?.tracks ?: emptyList()) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it })
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("YOUR ALBUMS & PLAYLISTS", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, null, tint = violet); Text("New album", color = violet) }
                            }
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                item {
                                    LibraryRow("Liked Songs", "${favorites.size} liked tracks", "♥", violet) { selectedPlaylist = "♥ Liked Songs" }
                                }
                                item {
                                    LibraryRow("My Songs", "${localSongs.size} imported tracks", "♫", Color(0xFF8E77C8)) { selectedPlaylist = "♪ My Songs" }
                                }
                                items(playlists, key = { it.name }) { pl ->
                                    LibraryRow(pl.name, "${pl.tracks.size} songs", "♫", Color(0xFF8E77C8)) { selectedPlaylist = pl.name }
                                }
                                if (playlists.isEmpty()) item { Text("Your albums will appear here. Create one, then add songs from Search or My Songs.", color = muted, fontSize = 13.sp) }
                            }
                        }
                        }
                    }
                    else -> {
                        if (selectedAlbum != null) {
                            Column(Modifier.fillMaxSize()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { selectedAlbum = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                                    Text("ALBUM", color = violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(selectedAlbum ?: "", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                                Text("${tracks.size} SONGS", color = muted, fontSize = 10.sp)
                                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
                                Text(message, color = muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 6.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { tracks.firstOrNull()?.let { play(it, tracks) } }) {
                                        Icon(Icons.Default.PlayArrow, null, tint = violet)
                                        Text("PLAY ALL", color = violet)
                                    }
                                }
                                TrackList(tracks, favorites, onPlay = { play(it, tracks) }, onLike = { toggleLike(it) }, onAdd = { addTrackTarget = it }, modifier = Modifier.weight(1f))
                            }
                        } else if (homeCollection == "Bangla Rock") {
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
                            onOpenAlbum = { title, term ->
                                selectedAlbum = title
                                query = term
                                search(term, true)
                            },
                            onPlay = { play(it, tracks) },
                            onLike = { toggleLike(it) },
                            onAdd = { addTrackTarget = it }
                        )
                    }
                }
                }

                current?.let { track ->
                    Column(Modifier.fillMaxWidth().clickable { showNowPlaying = true }.animateContentSize(tween(300, easing = FastOutSlowInEasing)).background(
                        Brush.horizontalGradient(listOf(Color(0xFF392752), Color(0xFF241B35), Color(0xFF392752))),
                        RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp)
                    ).padding(horizontal = 11.dp, vertical = 7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TrackArtwork(track.artworkUrl, Modifier.size(48.dp), 10.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 12.sp)
                                Text(track.artist, color = muted, fontSize = 10.sp, maxLines = 1)
                                Text(if (playing) "NOW PLAYING" else "PAUSED", color = violet, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                    BottomItem("Home", Icons.Default.Home, tab == "Home") { tab = "Home"; homeCollection = null; selectedAlbum = null }
                    BottomItem("Search", Icons.Default.Search, tab == "Search") { tab = "Search" }
                    BottomItem("Library", Icons.Default.LibraryMusic, tab == "Library") { tab = "Library"; selectedPlaylist = null }
                }
            }
        }

        if (showCreate) AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create album / playlist", color = Color.White) },
            text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Album / playlist name") }, singleLine = true) },
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
                title = { Text("Add song to album / playlist", color = Color.White) },
                text = {
                    Column {
                        if (playlists.isEmpty()) Text("Create an album in Your Library first.", color = muted)
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

    if (showNowPlaying) current?.let { track ->
        Dialog(onDismissRequest = { showNowPlaying = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            NowPlayingScreen(
                track = track, playing = playing, position = position, duration = duration,
                liked = favorites.any { it.id == track.id },
                onBack = { showNowPlaying = false },
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play(); playing = player.isPlaying },
                onPrevious = { player.seekToPrevious(); playing = true },
                onNext = { player.seekToNext(); playing = true },
                onSeek = { position = it; player.seekTo(it.toLong()) },
                onShuffle = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle },
                onRepeat = { repeat = !repeat; player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF },
                onLike = { toggleLike(track) }, shuffle = shuffle, repeat = repeat
            )
        }
    }

}

@Composable
private fun NowPlayingScreen(
    track: Track, playing: Boolean, position: Float, duration: Float, liked: Boolean,
    onBack: () -> Unit, onTogglePlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (Float) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onLike: () -> Unit,
    shuffle: Boolean, repeat: Boolean
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF302044), Color(0xFF120D1D), Color(0xFF07060B))))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 3.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("EARENDEL MUSIC", color = muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                }
                IconButton(onClick = onRepeat) { Icon(Icons.Default.QueueMusic, null, tint = if (repeat) violet else muted) }
            }
            Spacer(Modifier.height(13.dp))
            Box(
                Modifier.fillMaxWidth(.76f).aspectRatio(1f).clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4C3767), Color(0xFF171020))))
            ) {
                TrackArtwork(track.artworkUrl, Modifier.fillMaxSize(), 24.dp)
            }
            Spacer(Modifier.height(17.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text(track.artist, color = Color(0xFFC0B6D0), fontSize = 13.sp, maxLines = 1)
                }
                IconButton(onClick = onLike) { Icon(Icons.Default.Favorite, null, tint = if (liked) violet else Color.White, modifier = Modifier.size(25.dp)) }
            }
            Spacer(Modifier.height(7.dp))
            Slider(value = if (duration > 0) position.coerceIn(0f, duration) else 0f, onValueChange = onSeek,
                valueRange = 0f..duration.coerceAtLeast(1f), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = violet, inactiveTrackColor = Color(0xFF554B62)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(position), color = muted, fontSize = 10.sp)
                Text(timeLabel(duration), color = muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = if (shuffle) violet else Color.White) }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(66.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF8A54F5))) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                IconButton(onClick = onRepeat) { Icon(Icons.Default.Repeat, null, tint = if (repeat) violet else Color.White) }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = violet, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (playing) "YOU ARE LISTENING" else "PLAYBACK PAUSED", color = muted, fontSize = 9.sp, letterSpacing = 1.3.sp)
            }
        }
    }
}


@Composable
private fun AuthGate(onAuthenticated: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("welcome") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun finishEmailAuth() {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) { notice = "Please enter a valid email address."; return }
        if (password.length < 6) { notice = "Password must be at least 6 characters."; return }
        busy = true
        val task = if (page == "signup") auth.createUserWithEmailAndPassword(cleanEmail, password) else auth.signInWithEmailAndPassword(cleanEmail, password)
        task.addOnCompleteListener { result ->
            busy = false
            if (result.isSuccessful) onAuthenticated() else notice = result.exception?.localizedMessage ?: "Authentication failed. Please try again."
        }
    }
    fun startGoogleSignIn() {
        val idRes = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (idRes == 0) { notice = "Google sign-in setup is incomplete. Enable Google provider in Firebase and download the updated google-services.json."; return }
        val serverClientId = context.getString(idRes)
        if (serverClientId.isBlank()) { notice = "Google OAuth Web Client ID is missing. Update google-services.json from Firebase."; return }
        busy = true
        scope.launch {
            try {
                val option = GetGoogleIdOption.Builder().setServerClientId(serverClientId).setFilterByAuthorizedAccounts(false).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(context).getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).addOnCompleteListener { task ->
                        busy = false
                        if (task.isSuccessful) onAuthenticated() else notice = task.exception?.localizedMessage ?: "Google sign-in failed."
                    }
                } else { busy = false; notice = "Google did not return a valid sign-in credential." }
            } catch (error: GetCredentialException) { busy = false; notice = error.localizedMessage ?: "Google sign-in was cancelled or could not start."
            } catch (error: Exception) { busy = false; notice = error.localizedMessage ?: "Could not complete Google sign-in." }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.align(Alignment.TopEnd).size(230.dp).background(Brush.radialGradient(listOf(Color(0x553F168C), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomStart).size(260.dp).background(Brush.radialGradient(listOf(Color(0x553F168C), Color.Transparent))))
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(126.dp).background(Brush.radialGradient(listOf(Color(0xFF8E46FF), Color(0xFF29134A), Color.Black)), RoundedCornerShape(100)), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(62.dp)) }
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
                OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), label = { Text("Gmail or email address", color = Color.White) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, disabledTextColor = Color.White, cursorColor = violet, focusedLabelColor = violet, unfocusedLabelColor = Color.White, focusedBorderColor = violet, unfocusedBorderColor = Color(0xFF777080), focusedContainerColor = Color(0xFF17131F), unfocusedContainerColor = Color(0xFF17131F)))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text("Password", color = Color.White) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, disabledTextColor = Color.White, cursorColor = violet, focusedLabelColor = violet, unfocusedLabelColor = Color.White, focusedBorderColor = violet, unfocusedBorderColor = Color(0xFF777080), focusedContainerColor = Color(0xFF17131F), unfocusedContainerColor = Color(0xFF17131F)))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { finishEmailAuth() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF853CFF))) { Text(if (busy) "Please wait…" else if (page == "signup") "Create account" else "Log in") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { startGoogleSignIn() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("G  Continue with Google", color = Color.White) }
                TextButton(onClick = { page = if (page == "signup") "login" else "signup"; notice = "" }) { Text(if (page == "signup") "Already have an account? Log in" else "New to EARENDEL? Sign up free", color = violet) }
                if (notice.isNotBlank()) Text(notice, color = Color(0xFFFFC078), fontSize = 11.sp)
            }
            Spacer(Modifier.height(18.dp))
            if (page != "welcome") TextButton(onClick = { page = "welcome"; notice = "" }) { Text("Back", color = muted) }
        }
        Text(
            "EARENDEL  by  NAYEEM EVAN",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            color = Color(0xFF777080),
            fontSize = 9.sp,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Medium
        )
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
    onOpenAlbum: (String, String) -> Unit,
    onPlay: (Track) -> Unit,
    onLike: (Track) -> Unit,
    onAdd: (Track) -> Unit
) {
    Column(modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EARENDEL • YOUR MUSIC SPACE", color = violet, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Music for every\nmood.", color = Color.White, fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(Modifier.size(58.dp).background(Brush.linearGradient(listOf(Color(0xFF9865D5), Color(0xFF322044))), RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(15.dp))
        Spacer(Modifier.height(17.dp))
        Box(Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF68428D), Color(0xFF2C203B), Color(0xFF17131F)))).clickable { onOpenAlbum("Bangla Soundscape", "Bangla independent music") }) {
            Row(Modifier.fillMaxSize().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FEATURED COLLECTION", color = Color(0xFFE7D8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("THE SOUND\nOF BANGLA", color = Color.White, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(9.dp))
                    Text("Rock • Indie • Acoustic", color = Color(0xFFD5C9E4), fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("LISTEN NOW  →", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.size(104.dp).background(Color(0xFF3A2A50), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Album, null, tint = Color(0xFFE8DFFF), modifier = Modifier.size(65.dp))
                }
            }
        }
        Spacer(Modifier.height(21.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MADE FOR YOU", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("ALBUMS & MIXES", color = violet, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAlbumTile("Bangla Rock", "BAND ANTHEMS", Color(0xFF604078), Modifier.weight(1f), tracks.getOrNull(0)?.artworkUrl) { onOpenAlbum("Bangla Rock", "Bangla rock music") }
            HomeAlbumTile("Bangla Indie", "INDEPENDENT", Color(0xFF3F6661), Modifier.weight(1f), tracks.getOrNull(1)?.artworkUrl) { onOpenAlbum("Bangla Indie", "Bangla independent music") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAlbumTile("Hindi Melodies", "HINDI MIX", Color(0xFF79513D), Modifier.weight(1f), tracks.getOrNull(2)?.artworkUrl) { onOpenAlbum("Hindi Melodies", "Hindi independent music") }
            HomeAlbumTile("English Pop", "GLOBAL POP", Color(0xFF465B7C), Modifier.weight(1f), tracks.getOrNull(3)?.artworkUrl) { onOpenAlbum("English Pop", "English indie pop") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAlbumTile("Lofi & Chill", "CHILL BEATS", Color(0xFF554477), Modifier.weight(1f)) { onOpenAlbum("Lofi & Chill", "lofi chill beats") }
            HomeAlbumTile("Acoustic", "SOFT SOUNDS", Color(0xFF315D62), Modifier.weight(1f)) { onOpenAlbum("Acoustic", "acoustic instrumental music") }
        }
        Spacer(Modifier.height(22.dp))
        Text("BROWSE YOUR VIBE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HomeGenreTile("Bangla Rock", "ROCK", Color(0xFF624477), Modifier.weight(1f), onBanglaRock)
            HomeGenreTile("Hindi Mix", "HINDI", Color(0xFF77523D), Modifier.weight(1f)) { onOpenAlbum("Hindi Mix", "Hindi music") }
            HomeGenreTile("Lo-fi", "CHILL", Color(0xFF4E6B70), Modifier.weight(1f)) { onOpenAlbum("Lo-fi", "lofi music") }
        }
        Spacer(Modifier.height(21.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ALL SONGS", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${tracks.size} TRACKS", color = violet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text("Bangla, Bengali, Hindi & Bollywood selections", color = muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
        if (tracks.isEmpty()) Text("Loading songs for you...", color = muted, fontSize = 11.sp)
        tracks.take(60).forEach { track ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(panel, RoundedCornerShape(13.dp)).clickable { onPlay(track) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track.artworkUrl, Modifier.size(46.dp), 9.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(track.artist, color = muted, fontSize = 10.sp, maxLines = 1)
                }
                IconButton(onClick = { onLike(track) }) { Icon(Icons.Default.Favorite, null, tint = if (favorites.any { it.id == track.id }) violet else muted) }
                IconButton(onClick = { onAdd(track) }) { Icon(Icons.Default.PlaylistAdd, null, tint = muted) }
            }
        }
        Text(message, color = muted, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
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
private fun TrackArtwork(url: String, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(modifier.background(Color(0xFF35264D), RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = "Song cover art", modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.MusicNote, null, tint = violet, modifier = Modifier.size(25.dp))
        }
    }
}

private fun localTrackFromUri(context: android.content.Context, uri: android.net.Uri): Track {
    val retriever = MediaMetadataRetriever()
    var title = ""
    var artist = ""
    var artworkUrl = ""
    try {
        retriever.setDataSource(context, uri)
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
        val picture = retriever.embeddedPicture
        if (picture != null && picture.isNotEmpty()) {
            val artworkDir = File(context.filesDir, "song_artwork").apply { mkdirs() }
            val artworkFile = File(artworkDir, "${uri.toString().hashCode().toUInt()}.jpg")
            FileOutputStream(artworkFile).use { it.write(picture) }
            artworkUrl = artworkFile.toURI().toString()
        }
    } finally {
        try { retriever.release() } catch (_: Exception) { }
    }
    if (title.isBlank()) {
        title = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty().substringBeforeLast(".") else ""
            }.orEmpty()
        } catch (_: Exception) { "" }
    }
    if (title.isBlank()) title = "Local audio"
    if (artist.isBlank()) artist = "Unknown Artist"
    val id = "local-${uri.toString().hashCode().toUInt()}"
    return Track(id, title, artist, uri.toString(), artworkUrl)
}

private fun lookupAlbumCover(query: String): String {
    return try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder().url("https://api.deezer.com/search/album?q=$encoded").get().build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            val data = JSONObject(response.body?.string().orEmpty()).optJSONArray("data") ?: return@use ""
            if (data.length() == 0) return@use ""
            val album = data.getJSONObject(0)
            album.optString("cover_xl").takeIf { it.startsWith("https://") }
                ?: album.optString("cover_big").takeIf { it.startsWith("https://") }.orEmpty()
        }
    } catch (_: Exception) { "" }
}

@Composable
private fun HomeAlbumTile(title: String, label: String, tint: Color, modifier: Modifier, artworkUrl: String? = null, onClick: () -> Unit) {
    var resolvedArtwork by remember(title, artworkUrl) { mutableStateOf(artworkUrl.orEmpty()) }
    LaunchedEffect(title, artworkUrl) {
        if (resolvedArtwork.isBlank()) {
            resolvedArtwork = withContext(Dispatchers.IO) { lookupAlbumCover(title) }
        }
    }
    Column(modifier.animateContentSize(tween(280, easing = FastOutSlowInEasing)).background(Brush.linearGradient(listOf(tint, panel)), RoundedCornerShape(17.dp)).clickable { onClick() }.padding(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White.copy(alpha = .08f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            if (resolvedArtwork.isNotBlank()) {
                AsyncImage(model = resolvedArtwork, contentDescription = "$title cover art", modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tint, Color(0xFF171323)))), contentAlignment = Alignment.Center) {
                    Text(title.split(" ").take(2).joinToString("\n").uppercase(), color = Color.White, fontSize = 17.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
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
private fun TrackList(itemsList: List<Track>, favorites: List<Track>, onPlay: (Track) -> Unit, onLike: (Track) -> Unit, onAdd: (Track) -> Unit, modifier: Modifier = Modifier.heightIn(max = 390.dp)) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items(itemsList, key = { it.id }) { track ->
            Row(Modifier.fillMaxWidth().animateContentSize(tween(220)).background(panel, RoundedCornerShape(14.dp)).clickable { onPlay(track) }.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track.artworkUrl, Modifier.size(48.dp), 10.dp)
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
private fun trackJson(track: Track) = JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist).put("stream", track.stream).put("artworkUrl", track.artworkUrl)
private fun tracksJson(list: List<Track>) = JSONArray().also { arr -> list.forEach { arr.put(trackJson(it)) } }.toString()
private fun parseTracks(raw: String?): List<Track> = try {
    val arr = JSONArray(raw ?: "[]")
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Track(it.optString("id"), it.optString("title"), it.optString("artist"), it.optString("stream"), it.optString("artworkUrl")) } }
        .filterNot { it.id.startsWith("apple-") }
} catch (_: Exception) { emptyList() }
private fun readTracks(prefs: android.content.SharedPreferences, key: String) = parseTracks(prefs.getString(key, "[]"))
private fun playlistsJson(list: List<UserPlaylist>) = JSONArray().also { arr -> list.forEach { arr.put(JSONObject().put("name", it.name).put("tracks", JSONArray(tracksJson(it.tracks)))) } }.toString()
private fun readPlaylists(prefs: android.content.SharedPreferences): List<UserPlaylist> = try {
    val arr = JSONArray(prefs.getString("playlists", "[]"))
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { UserPlaylist(it.optString("name"), parseTracks(it.optJSONArray("tracks")?.toString())) } }
} catch (_: Exception) { emptyList() }

private fun searchOnline(term: String): List<Track> {
    val cleanTerm = term.trim()
    val collected = mutableListOf<Track>()
    // Deezer provides legal 30-second listening previews without requiring user login.
    val queries = listOf("Bangla", "Bengali", "Hindi", "Bollywood", cleanTerm).distinct()
    for (query in queries) {
        try {
            collected.addAll(searchDeezer(query, 25))
        } catch (_: Exception) { }
        if (collected.distinctBy { it.id }.size >= 60) break
    }
    // Fill any remaining slots with recordings whose Internet Archive item explicitly declares an open licence.
    if (collected.distinctBy { it.id }.size < 60) {
        val archiveQueries = listOf("Bengali folk music", "Bangla folk songs", "Hindi folk music", "Rabindra Sangeet")
        for (query in archiveQueries) {
            try { collected.addAll(searchArchive(query, 12)) } catch (_: Exception) { }
            if (collected.distinctBy { it.id }.size >= 60) break
        }
    }
    return collected.distinctBy { it.id }.take(60)
}

private fun searchDeezer(term: String, limit: Int = 25): List<Track> {
    val encoded = URLEncoder.encode(term, "UTF-8")
    val request = Request.Builder()
        .url("https://api.deezer.com/search?q=$encoded&limit=$limit")
        .header("User-Agent", "EARENDEL-MUSIC/2.0")
        .get().build()
    val body = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Music search failed: ${response.code}")
            response.body?.string() ?: error("Empty music search response")
        }
    val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
    val results = mutableListOf<Track>()
    for (i in 0 until data.length()) {
        val item = data.optJSONObject(i) ?: continue
        val preview = item.optString("preview")
        if (!preview.startsWith("https://")) continue
        val artist = item.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { "Unknown artist" }
        val album = item.optJSONObject("album")
        val cover = album?.optString("cover_xl").orEmpty().ifBlank { album?.optString("cover_big").orEmpty() }
        val title = item.optString("title").ifBlank { "Unknown track" }
        val id = item.optString("id").ifBlank { "$term-$i" }
        results.add(Track("deezer-$id", title, artist, preview, cover))
    }
    return results
}
private fun searchArchive(term: String, maxResults: Int = 60): List<Track> {
    val client = OkHttpClient.Builder()
        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Search each meaningful word independently; a phrase-only query was too restrictive.
    val terms = term.trim().split(Regex("\\s+"))
        .map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "") }
        .filter { it.length >= 2 }
        .distinct()
        .take(6)
    if (terms.isEmpty()) return emptyList()

    val termQuery = terms.joinToString(" OR ") { word ->
        "(title:$word OR creator:$word OR subject:$word)"
    }
    val query = "mediatype:audio AND ($termQuery)"
    val url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(query, "UTF-8") +
        "&fl[]=identifier&fl[]=title&fl[]=creator&rows=$maxResults&page=1&output=json"
    val req = Request.Builder().url(url).header("User-Agent", "EARENDEL-MUSIC/1.4").build()
    val body = client.newCall(req).execute().use { response ->
        if (!response.isSuccessful) error("Archive HTTP " + response.code)
        response.body?.string() ?: error("Empty archive response")
    }
    val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
    val results = mutableListOf<Track>()
    for (i in 0 until docs.length()) {
        val doc = docs.getJSONObject(i)
        val id = doc.optString("identifier")
        if (id.isBlank()) continue
        try {
            val metaReq = Request.Builder()
                .url("https://archive.org/metadata/$id")
                .header("User-Agent", "EARENDEL-MUSIC/1.4")
                .build()
            val metaBody = client.newCall(metaReq).execute().use { response ->
                if (!response.isSuccessful) "" else response.body?.string() ?: ""
            }
            if (metaBody.isBlank()) continue
            val metadata = JSONObject(metaBody)
            val itemMeta = metadata.optJSONObject("metadata") ?: JSONObject()
            val licenseUrl = itemMeta.optString("licenseurl").lowercase()
            val rights = itemMeta.optString("rights").lowercase()
            // Keep only items whose uploader explicitly declares a CC or public-domain licence.
            val openlyLicensed = licenseUrl.contains("creativecommons.org/licenses/") ||
                licenseUrl.contains("creativecommons.org/publicdomain/") ||
                rights.contains("creative commons") || rights.contains("public domain") ||
                rights.contains("publicdomain")
            if (!openlyLicensed) continue

            val files = metadata.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val file = files.getJSONObject(j)
                val name = file.optString("name")
                val isAudio = name.endsWith(".mp3", true) || name.endsWith(".ogg", true) ||
                    name.endsWith(".m4a", true) || name.endsWith(".flac", true) || name.endsWith(".wav", true)
                if (!isAudio || file.optString("private") == "true" ||
                    file.optString("source") == "metadata") continue

                val rawLength = file.optString("length")
                val seconds = rawLength.toDoubleOrNull() ?: run {
                    val parts = rawLength.split(":")
                    if (parts.size in 2..3) {
                        parts.mapNotNull { it.toDoubleOrNull() }.let { values ->
                            if (values.size == parts.size) values.reversed().mapIndexed { index, value ->
                                value * when (index) { 0 -> 1.0; 1 -> 60.0; else -> 3600.0 }
                            }.sum() else 0.0
                        }
                    } else 0.0
                }
                // Exclude short previews and clips.
                if (seconds < 90.0) continue

                val title = doc.optString("title", id).ifBlank { id }
                val creatorValue = doc.opt("creator")
                val artist = when (creatorValue) {
                    is JSONArray -> (0 until creatorValue.length()).joinToString(", ") { creatorValue.optString(it) }
                    is String -> creatorValue
                    else -> "Independent artist"
                }.ifBlank { "Independent artist" }
                val streamUrl = "https://archive.org/download/" + id + "/" + URLEncoder.encode(name, "UTF-8")
                val artwork = "https://archive.org/services/img/$id"
                results.add(Track("$id-$name", title, artist, streamUrl, artwork))
                break
            }
        } catch (_: Exception) { }
    }
    return results.distinctBy { it.title.trim().lowercase() + "|" + it.artist.trim().lowercase() }
}
