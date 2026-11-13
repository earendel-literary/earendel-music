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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class Track(val id: String, val title: String, val artist: String, val stream: String)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { EarendelMusic(player) }
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

private val ink = Color(0xFF090812)
private val panel = Color(0xFF191526)
private val violet = Color(0xFF9B7BFF)
private val muted = Color(0xFFAAA4B9)

@Composable
fun EarendelMusic(player: ExoPlayer) {
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var current by remember { mutableStateOf<Track?>(null) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search Creative Commons music from the Internet Archive.") }
    var tab by remember { mutableStateOf("Discover") }
    val scope = rememberCoroutineScope()
    val favorites = remember { mutableStateListOf<Track>() }

    fun search(term: String) {
        if (term.isBlank()) return
        loading = true
        message = "Searching licensed audio…"
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { searchArchive(term) }
                tracks = found
                message = if (found.isEmpty()) "No matching Creative Commons audio found. Try another search." else "${found.size} tracks found"
            } catch (e: Exception) {
                message = "Could not load music. Check your internet connection and try again."
            } finally { loading = false }
        }
    }
    fun play(track: Track) {
        current = track
        player.setMediaItem(MediaItem.fromUri(track.stream))
        player.prepare()
        player.play()
        playing = true
    }

    MaterialTheme(colorScheme = darkColorScheme(background = ink, surface = panel, primary = violet, onBackground = Color.White, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF211735), ink, ink))).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("EARENDEL", color = violet, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("MUSIC", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                }
                Text("∞", color = violet, fontSize = 38.sp)
            }
            Spacer(Modifier.height(22.dp))
            Text("Find your sound.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Discover music you can legally stream.", color = muted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = violet)
                Spacer(Modifier.width(8.dp))
                BasicTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp), decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search songs, artists…", color = muted)
                    inner()
                })
                TextButton(onClick = { search(query) }) { Text("SEARCH", color = violet, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(17.dp))
            Text("EXPLORE BY MOOD / ARTIST", color = muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ASHES", "Artcell", "Highway", "Shonar Bangla Circus").forEach { name ->
                    Surface(color = panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { query = name; search(name) }) {
                        Text(name, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (tab == "Favorites") "YOUR FAVORITES" else "DISCOVER", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { tab = if (tab == "Favorites") "Discover" else "Favorites" }) { Text(if (tab == "Favorites") "DISCOVER" else "FAVORITES", color = violet) }
            }
            Text(message, color = muted, fontSize = 12.sp)
            val visible = if (tab == "Favorites") favorites.toList() else tracks
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = violet)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { track ->
                    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).clickable { play(track) }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Color(0xFF33264D), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, tint = violet)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { if (favorites.any { it.id == track.id }) favorites.removeAll { it.id == track.id } else favorites.add(track) }) {
                            Icon(Icons.Default.Favorite, null, tint = if (favorites.any { it.id == track.id }) violet else muted)
                        }
                    }
                }
            }
            current?.let { track ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF302344), RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NOW PLAYING", color = violet, fontSize = 9.sp, letterSpacing = 2.sp)
                        Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(track.artist, color = muted, fontSize = 11.sp, maxLines = 1)
                    }
                    IconButton(onClick = { if (player.isPlaying) { player.pause(); playing = false } else { player.play(); playing = true } }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Tracks stream from their original archive source. Availability depends on uploader licensing and access.", color = muted, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
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
