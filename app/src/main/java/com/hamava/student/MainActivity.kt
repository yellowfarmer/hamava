package com.hamava.student

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder

private val Teal = Color(0xFF176F63)
private val DeepTeal = Color(0xFF104F48)
private val Mint = Color(0xFFE6F3EF)
private val CanvasBg = Color(0xFFF3F7F5)
private val Ink = Color(0xFF1D302C)
private val Muted = Color(0xFF6D7F79)
private val Line = Color(0xFFDCE7E2)
private val Coral = Color(0xFFC54F47)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(243, 247, 245)
        window.navigationBarColor = android.graphics.Color.rgb(243, 247, 245)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContent { HamavaTheme { HamavaStudentApp() } }
    }
}

@Composable
private fun HamavaTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Teal, onPrimary = Color.White, secondary = DeepTeal,
        background = CanvasBg, surface = Color.White, onSurface = Ink,
        onBackground = Ink, outline = Line, error = Coral
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

private data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val year: String = "",
    val album: String = "",
    val genre: String = "",
    val note: String = "",
    val audioUrl: String,
    val sha: String = "",
    val localName: String,
    val lrc: String = ""
)

private data class LyricLine(val seconds: Double?, val text: String)
private data class PitchReading(val note: String, val cents: Int, val hz: Double)

/** Local-first course library. The remote catalogue is published by a teacher in a public GitHub repo. */
private class SongStore(private val context: Context) {
    private val home = File(context.filesDir, "hamava")
    private val musicDir = File(home, "library")
    private val metadataFile = File(home, "song-index.json")
    private val prefs = context.getSharedPreferences("hamava-settings", Context.MODE_PRIVATE)

    init { home.mkdirs(); musicDir.mkdirs(); File(home, "recordings").mkdirs() }
    fun savedRepo(): String = prefs.getString("github_repo", "") ?: ""
    fun saveRepo(value: String) { prefs.edit().putString("github_repo", value.trim()).apply() }
    fun localAudio(song: Song): File = File(musicDir, song.localName)

    fun loadSongs(): List<Song> {
        return try {
            if (!metadataFile.exists()) emptyList()
            else {
                val arr = JSONArray(metadataFile.readText())
                (0 until arr.length()).map { i -> songFromJson(arr.getJSONObject(i)) }
                    .filter { localAudio(it).exists() }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun songFromJson(o: JSONObject) = Song(
        id = o.optString("id"), title = o.optString("title", "بدون عنوان"),
        artist = o.optString("artist"), year = o.optString("year"), album = o.optString("album"),
        genre = o.optString("genre"), note = o.optString("note"), audioUrl = o.optString("audioUrl"),
        sha = o.optString("sha"), localName = o.optString("localName"), lrc = o.optString("lrc")
    )

    private fun songJson(s: Song) = JSONObject().apply {
        put("id", s.id); put("title", s.title); put("artist", s.artist); put("year", s.year)
        put("album", s.album); put("genre", s.genre); put("note", s.note); put("audioUrl", s.audioUrl)
        put("sha", s.sha); put("localName", s.localName); put("lrc", s.lrc)
    }

    fun sync(repoUrl: String): List<Song> {
        val repo = GitHubRepo.parse(repoUrl)
        val branch = repo.branch ?: httpJson("https://api.github.com/repos/${repo.owner}/${repo.name}").optString("default_branch", "main")
        val basePath = repo.path.trim('/')
        val listUrl = contentsApi(repo, branch, basePath)
        val contents = httpJsonValue(listUrl) as? JSONArray
            ?: error("در مسیر انتخاب‌شده، فهرست فایل‌ها دریافت نشد.")
        val entries = (0 until contents.length()).map { contents.getJSONObject(it) }
        val entryByPath = entries.filter { it.optString("type") == "file" }.associateBy { it.optString("path").removePrefix("${basePath}/") }
        val old = loadSongs().associateBy { it.id }

        val manifestEntry = entryByPath["catalog.json"] ?: entryByPath["manifest.json"]
        val remoteDefs = mutableListOf<RemoteSong>()
        if (manifestEntry != null) {
            val manifest = JSONObject(httpText(manifestEntry.optString("download_url")))
            val array = manifest.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val audioRef = item.optString("audio", item.optString("file", "")).trim()
                if (audioRef.isBlank()) continue
                val resolved = resolveRemoteFile(repo, branch, basePath, audioRef, entryByPath)
                val lyricsRef = item.optString("lyrics", item.optString("lrc", "")).trim()
                var lrc = item.optString("lyricsText", item.optString("lyrics_text", ""))
                if (lrc.isBlank() && lyricsRef.isNotBlank() && !lyricsRef.startsWith("[")) {
                    lrc = runCatching { httpText(resolveRemoteFile(repo, branch, basePath, lyricsRef, entryByPath).url) }.getOrDefault("")
                } else if (lrc.isBlank() && lyricsRef.startsWith("[")) lrc = lyricsRef
                if (lrc.isBlank()) {
                    val stem = audioRef.substringAfterLast('/').substringBeforeLast('.')
                    val sidecar = entryByPath["$stem.lrc"]
                    if (sidecar != null) lrc = runCatching { httpText(sidecar.optString("download_url")) }.getOrDefault("")
                }
                val title = item.optString("title").ifBlank { audioRef.substringAfterLast('/').substringBeforeLast('.') }
                remoteDefs += RemoteSong(
                    id = item.optString("id").ifBlank { audioRef }, title = title, artist = item.optString("artist"), year = item.optString("year"),
                    album = item.optString("album"), genre = item.optString("genre"), note = item.optString("note"),
                    url = resolved.url, sha = item.optString("sha").ifBlank { resolved.sha }, lrc = lrc,
                    extension = audioRef.substringAfterLast('.', "mp3").substringBefore('?').take(8)
                )
            }
        } else {
            val audioFiles = entries.filter { it.optString("type") == "file" && it.optString("name").substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
            val lrcByStem = entries.filter { it.optString("name").endsWith(".lrc", true) }.associateBy { it.optString("name").substringBeforeLast('.') }
            for (e in audioFiles) {
                val name = e.optString("name")
                val stem = name.substringBeforeLast('.')
                val parts = stem.split(" - ", limit = 2)
                val sidecar = lrcByStem[stem]
                val lyricText = if (sidecar != null) runCatching { httpText(sidecar.optString("download_url")) }.getOrDefault("") else ""
                remoteDefs += RemoteSong(
                    id = e.optString("path").ifBlank { name },
                    title = if (parts.size == 2) parts[1] else stem,
                    artist = if (parts.size == 2) parts[0] else "",
                    url = e.optString("download_url"), sha = e.optString("sha"), lrc = lyricText,
                    extension = name.substringAfterLast('.', "mp3").lowercase()
                )
            }
        }
        require(remoteDefs.isNotEmpty()) {
            "در این مسیر آهنگی پیدا نشد. فایل صوتی را در ریشه بگذارید یا catalog.json بسازید."
        }

        val synced = mutableListOf<Song>()
        for (remote in remoteDefs) {
            val prior = old[remote.id]
            val localName = prior?.localName ?: (sha256(remote.id).take(24) + "." + remote.extension)
            val destination = File(musicDir, localName)
            val audioChanged = !destination.exists() || remote.sha.isBlank() || remote.sha != prior?.sha
            if (audioChanged) {
                val tmp = File(musicDir, "$localName.part")
                try {
                    downloadTo(remote.url, tmp)
                    if (tmp.length() <= 0L) error("فایل صوتی خالی است")
                    if (destination.exists()) destination.delete()
                    if (!tmp.renameTo(destination)) {
                        tmp.copyTo(destination, overwrite = true); tmp.delete()
                    }
                } catch (ex: Exception) {
                    tmp.delete()
                    if (!destination.exists()) throw ex
                }
            }
            synced += Song(
                id = remote.id, title = remote.title, artist = remote.artist, year = remote.year,
                album = remote.album, genre = remote.genre, note = remote.note, audioUrl = remote.url,
                sha = remote.sha, localName = localName, lrc = remote.lrc.ifBlank { prior?.lrc ?: "" }
            )
        }
        val array = JSONArray(); synced.forEach { array.put(songJson(it)) }
        metadataFile.writeText(array.toString())
        // Remove course tracks that were explicitly removed from the repository only after a complete sync.
        val keep = synced.map { it.localName }.toSet()
        musicDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.name !in keep }?.forEach { it.delete() }
        return synced
    }

    private fun resolveRemoteFile(repo: GitHubRepo, branch: String, basePath: String, ref: String, root: Map<String, JSONObject>): ResolvedFile {
        if (ref.startsWith("https://") || ref.startsWith("http://")) return ResolvedFile(ref, "")
        val clean = ref.trimStart('/')
        val candidates = listOf(clean, if (basePath.isBlank()) clean else "$basePath/$clean")
        for (candidate in candidates) {
            val direct = root[candidate.removePrefix("${basePath}/")] ?: root[candidate]
            if (direct != null && direct.optString("download_url").isNotBlank()) return ResolvedFile(direct.optString("download_url"), direct.optString("sha"))
        }
        val path = if (clean.startsWith("$basePath/")) clean else listOf(basePath, clean).filter { it.isNotBlank() }.joinToString("/")
        val value = httpJsonValue(contentsApi(repo, branch, path))
        val fileObj = value as? JSONObject ?: error("فایل پیدا نشد: $ref")
        return ResolvedFile(fileObj.optString("download_url"), fileObj.optString("sha"))
    }

    private fun contentsApi(repo: GitHubRepo, branch: String, path: String): String {
        val suffix = path.split('/').filter { it.isNotBlank() }.joinToString("/") { enc(it) }
        val base = "https://api.github.com/repos/${repo.owner}/${repo.name}/contents" + if (suffix.isBlank()) "" else "/$suffix"
        return "$base?ref=${enc(branch)}"
    }

    private fun httpJson(url: String): JSONObject = httpJsonValue(url) as? JSONObject ?: error("پاسخ نامعتبر از GitHub")
    private fun httpJsonValue(url: String): Any? = JSONTokener(httpText(url)).nextValue()

    private fun httpText(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; requestMethod = "GET"
            setRequestProperty("User-Agent", "Hamava-Student-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("GitHub پاسخ $code داد: ${body.take(180)}")
            return body
        } finally { c.disconnect() }
    }

    private fun downloadTo(url: String, dest: File) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 90000; requestMethod = "GET"
            setRequestProperty("User-Agent", "Hamava-Student-Android")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) error("دانلود آهنگ ناموفق بود ($code)")
            BufferedInputStream(c.inputStream).use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
        } finally { c.disconnect() }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class ResolvedFile(val url: String, val sha: String)
    private data class RemoteSong(
        val id: String, val title: String, val artist: String = "", val year: String = "", val album: String = "",
        val genre: String = "", val note: String = "", val url: String, val sha: String = "", val lrc: String = "", val extension: String = "mp3"
    )

    companion object { val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus") }
}

private data class GitHubRepo(val owner: String, val name: String, val branch: String?, val path: String) {
    companion object {
        fun parse(input: String): GitHubRepo {
            val clean = input.trim().removeSuffix("/").removeSuffix(".git")
            val match = Regex("(?:https?://)?(?:www\\.)?github\\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(?:/(.*))?)?/?$").find(clean)
                ?: error("لطفاً آدرس یک مخزن GitHub مثل https://github.com/username/songs را وارد کنید.")
            val branch = match.groupValues[3].ifBlank { null }
            return GitHubRepo(match.groupValues[1], match.groupValues[2].substringBefore('?'), branch, match.groupValues[4])
        }
    }
}

private class Mp3Recorder(private val context: Context) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var output: File? = null
    private var error: Exception? = null

    fun start(): File {
        check(!running) { "ضبط از قبل شروع شده است." }
        val dir = File(context.filesDir, "hamava/recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        output = File(dir, "hamava-$stamp.mp3")
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "ضبط صدا روی این دستگاه پشتیبانی نمی‌شود." }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer * 2, 8192))
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "میکروفون آماده نشد." }
        recorder = audio
        running = true
        error = null
        audio.startRecording()
        worker = Thread {
            var lame: AndroidLame? = null
            try {
                val encoder = LameBuilder().setInSampleRate(SAMPLE_RATE).setOutSampleRate(SAMPLE_RATE)
                    .setOutChannels(1).setOutBitrate(128).setQuality(5).setMode(LameBuilder.Mode.MONO).build()
                lame = encoder
                val pcm = ShortArray(4096)
                val mp3 = ByteArray(8192)
                FileOutputStream(output!!).use { out ->
                    while (running) {
                        val count = audio.read(pcm, 0, pcm.size)
                        if (count > 0) {
                            val bytes = encoder.encode(pcm, pcm, count, mp3)
                            if (bytes > 0) out.write(mp3, 0, bytes)
                        }
                    }
                    val flushed = encoder.flush(mp3)
                    if (flushed > 0) out.write(mp3, 0, flushed)
                    out.flush()
                }
            } catch (e: Exception) { if (running) error = e }
            finally { try { lame?.close() } catch (_: Exception) {} }
        }.apply { name = "HamavaMp3Encoder"; start() }
        return output!!
    }

    fun stop(onComplete: (File?, String?) -> Unit) {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { worker?.join(4000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null; worker = null
        val file = output
        val issue = error
        Handler(Looper.getMainLooper()).post {
            if (issue == null && file != null && file.exists() && file.length() > 0) onComplete(file, null)
            else onComplete(null, issue?.message ?: "فایل ضبط ساخته نشد")
        }
    }

    companion object { private const val SAMPLE_RATE = 44100 }
}

private class TunerController(private val onPitch: (PitchReading?) -> Unit, private val onError: (String) -> Unit, private val referenceHz: () -> Double) {
    @Volatile private var running = false
    private var audio: AudioRecord? = null
    private var thread: Thread? = null
    fun start() {
        if (running) return
        val rate = 44100
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { onError("تیونر روی این دستگاه قابل شروع نیست."); return }
        val a = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, 8192))
        if (a.state != AudioRecord.STATE_INITIALIZED) { a.release(); onError("میکروفون آماده نشد."); return }
        audio = a; running = true
        thread = Thread {
            try {
                a.startRecording()
                val raw = ShortArray(4096)
                while (running) {
                    val n = a.read(raw, 0, raw.size)
                    if (n > 0) {
                        val pitch = estimatePitch(raw, n, rate, referenceHz().coerceIn(415.0, 466.0))
                        Handler(Looper.getMainLooper()).post { onPitch(pitch) }
                    }
                }
            } catch (e: Exception) { if (running) Handler(Looper.getMainLooper()).post { onError(e.message ?: "خطای تیونر") } }
        }.apply { name = "HamavaTuner"; start() }
    }
    fun stop() {
        running = false
        try { audio?.stop() } catch (_: Exception) {}
        try { thread?.join(700) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { audio?.release() } catch (_: Exception) {}
        audio = null; thread = null
        Handler(Looper.getMainLooper()).post { onPitch(null) }
    }
}

private fun estimatePitch(source: ShortArray, length: Int, sampleRate: Int, referenceHz: Double = 440.0): PitchReading? {
    if (length < 512) return null
    val x = FloatArray(length / 2) { source[it * 2].toFloat() / 32768f }
    val n = x.size
    var energy = 0.0
    for (v in x) energy += v * v
    if (sqrt(energy / n) < 0.008) return null
    val minLag = max(2, (sampleRate / 2) / 1000)
    val sr = sampleRate / 2
    val maxLag = min(n / 2, sr / 65)
    var bestLag = 0
    var best = 0.0
    for (lag in minLag..maxLag) {
        var corr = 0.0; var a = 0.0; var b = 0.0
        val upto = n - lag
        var i = 0
        while (i < upto) {
            val p = x[i].toDouble(); val q = x[i + lag].toDouble()
            corr += p * q; a += p * p; b += q * q; i += 2
        }
        val score = corr / sqrt((a * b).coerceAtLeast(1e-12))
        if (score > best) { best = score; bestLag = lag }
    }
    if (bestLag == 0 || best < 0.65) return null
    val hz = sr.toDouble() / bestLag
    if (hz !in 55.0..1100.0) return null
    val midi = 69.0 + 12.0 * log2(hz / referenceHz)
    val nearest = kotlin.math.round(midi).toInt()
    val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return PitchReading(names[(nearest % 12 + 12) % 12] + (nearest / 12 - 1), ((midi - nearest) * 100).toInt(), hz)
}

private fun parseLyrics(text: String): List<LyricLine> {
    val result = mutableListOf<LyricLine>()
    val regex = Regex("\\[(\\d+):(\\d+(?:[.,]\\d+)?)\\]")
    text.lineSequence().forEach { line ->
        val stamps = regex.findAll(line).toList()
        val lyric = regex.replace(line, "").replace(Regex("\\[[^]]*]"), "").trim()
        if (stamps.isNotEmpty()) stamps.forEach { m ->
            val seconds = m.groupValues[1].toDouble() * 60 + m.groupValues[2].replace(',', '.').toDouble()
            result += LyricLine(seconds, lyric)
        } else if (line.isNotBlank()) result += LyricLine(null, line.trim())
    }
    return if (result.any { it.seconds != null }) result.filter { it.seconds != null }.sortedBy { it.seconds } else result
}

@Composable
private fun HamavaStudentApp() {
    val context = LocalContext.current
    val store = remember { SongStore(context) }
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf(store.loadSongs()) }
    var selectedId by remember { mutableStateOf(songs.firstOrNull()?.id ?: "") }
    var records by remember { mutableStateOf(File(context.filesDir, "hamava/recordings").listFiles()?.filter { it.extension.equals("mp3", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    var repo by remember { mutableStateOf(store.savedRepo()) }
    var repoDraft by remember { mutableStateOf(repo) }
    var showSettings by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf("songs") }
    var busy by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf(if (songs.isNotEmpty()) "کتابخانه روی دستگاه آماده است" else "برای دریافت آهنگ‌ها، مخزن GitHub را تنظیم کنید") }
    var recording by remember { mutableStateOf(false) }
    var recordStart by remember { mutableLongStateOf(0L) }
    var recordElapsed by remember { mutableLongStateOf(0L) }
    var tunerOn by remember { mutableStateOf(false) }
    var pitch by remember { mutableStateOf<PitchReading?>(null) }
    var reference by remember { mutableStateOf("440") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var looping by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0.7f) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<Mp3Recorder?>(null) }
    var tuner by remember { mutableStateOf<TunerController?>(null) }
    val selected = songs.find { it.id == selectedId }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (activeTab == "tuner") {
                tuner = TunerController({ pitch = it }, { message = it; messageError = true }) { (reference.toIntOrNull() ?: 440).toDouble() }.also { it.start() }
                tunerOn = true
            } else {
                try {
                    recorder = Mp3Recorder(context).also { it.start() }
                    recording = true; recordStart = System.currentTimeMillis(); recordElapsed = 0L
                } catch (e: Exception) { message = e.message ?: "ضبط شروع نشد"; messageError = true }
            }
        } else { message = "برای ضبط یا تیونر باید اجازهٔ میکروفون را بدهید."; messageError = true }
    }

    fun refreshRecordings() {
        records = File(context.filesDir, "hamava/recordings").listFiles()?.filter { it.extension.equals("mp3", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    fun changeTab(tab: String) {
        if (tab != "tuner" && tunerOn) {
            tuner?.stop(); tuner = null; tunerOn = false; pitch = null
        }
        if (tab != "records" && recording) {
            recording = false
            val activeRecorder = recorder
            recorder = null
            activeRecorder?.stop { file, error ->
                refreshRecordings()
                if (file != null) { message = "ضبط به‌صورت MP3 ذخیره شد."; messageError = false }
                else { message = error ?: "ضبط ذخیره نشد"; messageError = true }
            }
        }
        activeTab = tab
    }
    fun syncLibrary() {
        if (repo.isBlank()) { repoDraft = repo; showSettings = true; return }
        scope.launch {
            busy = true; syncMessage = "در حال بررسی GitHub و به‌روزرسانی کتابخانه…"
            try {
                val synced = withContext(Dispatchers.IO) { store.sync(repo) }
                songs = synced
                if (selectedId !in synced.map { it.id }) selectedId = synced.firstOrNull()?.id ?: ""
                syncMessage = "کتابخانه به‌روز شد · ${synced.size} آهنگ برای استفادهٔ آفلاین"
                message = syncMessage; messageError = false
            } catch (e: Exception) {
                songs = store.loadSongs()
                if (selectedId.isBlank()) selectedId = songs.firstOrNull()?.id ?: ""
                syncMessage = "همگام‌سازی انجام نشد؛ نسخهٔ ذخیره‌شده حفظ شد."
                message = e.message ?: syncMessage; messageError = true
            } finally { busy = false }
        }
    }
    fun stopPlayer() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null; playing = false; preparing = false; positionMs = 0; durationMs = 0
    }
    fun playSelected(song: Song) {
        val file = store.localAudio(song)
        if (!file.exists()) { message = "این آهنگ هنوز روی دستگاه ذخیره نشده است. اینترنت را وصل و همگام‌سازی کنید."; messageError = true; return }
        stopPlayer()
        try {
            preparing = true
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = looping
                setVolume(volume, volume)
                setOnPreparedListener { mp -> mp.start(); durationMs = mp.duration; preparing = false; playing = true }
                setOnCompletionListener { playing = false; positionMs = 0 }
                setOnErrorListener { _, _, _ -> preparing = false; playing = false; message = "پخش این فایل ممکن نشد."; messageError = true; true }
                prepareAsync()
            }
        } catch (e: Exception) { preparing = false; message = e.message ?: "پخش آغاز نشد"; messageError = true }
    }
    fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "ارسال ضبط با…"))
        } catch (e: Exception) { message = e.message ?: "ارسال فایل ممکن نشد"; messageError = true }
    }

    DisposableEffect(Unit) {
        onDispose { try { player?.release() } catch (_: Exception) {}; recorder?.stop { _, _ -> }; tuner?.stop() }
    }
    LaunchedEffect(playing, preparing) {
        while (playing && !preparing) {
            positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(250)
        }
    }
    LaunchedEffect(recording) {
        while (recording) { recordElapsed = System.currentTimeMillis() - recordStart; delay(250) }
    }
    LaunchedEffect(Unit) {
        if (repo.isNotBlank()) syncLibrary()
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(containerColor = CanvasBg, snackbarHost = {}) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                Header(
                    busy = busy, syncMessage = syncMessage,
                    onSettings = { repoDraft = repo; showSettings = true },
                    onSync = { syncLibrary() }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NavPill("آهنگ‌ها", Icons.Default.LibraryMusic, activeTab == "songs", Modifier.weight(1f)) { changeTab("songs") }
                    NavPill("ضبط‌های من", Icons.Default.Mic, activeTab == "records", Modifier.weight(1f)) { changeTab("records") }
                    NavPill("تیونر", Icons.Default.Tune, activeTab == "tuner", Modifier.weight(1f)) { changeTab("tuner") }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (activeTab) {
                        "songs" -> {
                            PlayerCard(
                                song = selected, playing = playing, preparing = preparing,
                                position = positionMs, duration = durationMs, looping = looping, volume = volume,
                                onLoop = { looping = !looping; runCatching { player?.isLooping = looping } },
                                onVolume = { volume = it.coerceIn(0f, 1f); runCatching { player?.setVolume(volume, volume) } },
                                onToggle = { if (player == null) selected?.let { playSelected(it) } else if (playing) { player?.pause(); playing = false } else { player?.start(); playing = true } },
                                onSeek = { positionMs = it; runCatching { if (durationMs > 0) player?.seekTo(it) } },
                                onBack = { runCatching { player?.seekTo(max(0, (player?.currentPosition ?: 0) - 5000)) } },
                                onForward = { runCatching { player?.seekTo(min(durationMs, (player?.currentPosition ?: 0) + 5000)) } },
                                onStop = { stopPlayer() },
                                onSpeed = { v -> runCatching { player?.playbackParams = (player?.playbackParams ?: android.media.PlaybackParams()).setSpeed(v) } }
                            )
                            if (selected != null && selected.lrc.isNotBlank()) LyricsCard(selected.lrc, positionMs / 1000.0) { sec -> runCatching { player?.seekTo((sec * 1000).toInt()) } }
                            LibraryCard(songs, selectedId, busy, onSelect = { id ->
                                selectedId = id; stopPlayer()
                            }, onSync = { syncLibrary() }, onSettings = { repoDraft = repo; showSettings = true })
                            if (songs.isEmpty()) InfoCard(
                                title = "کتابخانهٔ درس شما",
                                body = "پس از تنظیم آدرس مخزن GitHub، آهنگ‌ها و اطلاعات آن‌ها روی گوشی ذخیره می‌شوند و برای تمرین آفلاین در دسترس خواهند بود."
                            )
                        }
                        "records" -> {
                            RecorderCard(recording, recordElapsed, onRecord = {
                                if (recording) {
                                    recording = false
                                    recorder?.stop { file, error ->
                                        recorder = null; refreshRecordings()
                                        if (file != null) { message = "ضبط به‌صورت MP3 ذخیره شد."; messageError = false }
                                        else { message = error ?: "ضبط ذخیره نشد"; messageError = true }
                                    }
                                } else {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        try { recorder = Mp3Recorder(context).also { it.start() }; recording = true; recordStart = System.currentTimeMillis() }
                                        catch (e: Exception) { message = e.message ?: "ضبط شروع نشد"; messageError = true }
                                    } else microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            })
                            RecordingsCard(records, onShare = { shareFile(it) }, onPlay = { file ->
                                stopPlayer()
                                try {
                                    player = MediaPlayer().apply { setDataSource(file.absolutePath); setOnPreparedListener { p -> p.start(); playing = true; preparing = false }; setOnCompletionListener { playing = false }; prepareAsync() }
                                    preparing = true
                                } catch (e: Exception) { message = e.message ?: "پخش ضبط انجام نشد"; messageError = true }
                            }, playingFileName = "")
                        }
                        else -> {
                            TunerCard(
                                active = tunerOn, pitch = pitch, reference = reference,
                                onReference = { reference = it },
                                onToggle = {
                                    if (tunerOn) { tuner?.stop(); tuner = null; tunerOn = false; pitch = null }
                                    else {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            tuner = TunerController({ pitch = it }, { message = it; messageError = true }) { (reference.toIntOrNull() ?: 440).toDouble() }.also { it.start() }; tunerOn = true
                                        } else microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            )
                            InfoCard("راهنمای کوک", "یک نت را به‌صورت یکنواخت بخوانید. نشانگر نزدیک مرکز یعنی کوک دقیق‌تر است. از هدفون استفاده کنید تا صدای آهنگ وارد میکروفون نشود.")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (message.isNotBlank()) {
                    Surface(color = if (messageError) Color(0xFFFFF0EE) else Mint, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), color = if (messageError) Coral else DeepTeal, fontSize = 12.sp)
                            TextButton(onClick = { message = "" }) { Text("باشه") }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("تنظیم کتابخانهٔ درس") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("آدرس مخزن عمومی GitHub را وارد کنید. برنامه فهرست آهنگ‌ها را بررسی می‌کند و فایل‌ها را برای استفادهٔ آفلاین نگه می‌دارد.", fontSize = 13.sp, color = Muted)
                        OutlinedTextField(value = repoDraft, onValueChange = { repoDraft = it }, label = { Text("آدرس مخزن یا پوشهٔ songs") }, placeholder = { Text("https://github.com/username/repository") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("مثال پوشه: https://github.com/user/repo/tree/main/songs", fontSize = 11.sp, color = Muted)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    repo = repoDraft.trim(); store.saveRepo(repo); showSettings = false
                    if (repo.isNotBlank()) syncLibrary()
                }) { Text("ذخیره و همگام‌سازی") } },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("انصراف") } }
            )
        }
    }
}

@Composable
private fun Header(busy: Boolean, syncMessage: String, onSettings: () -> Unit, onSync: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Teal), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("هم‌آوا", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 26.sp)
            Text("استودیوی تمرین آواز", fontSize = 11.sp, color = Muted)
        }
        IconButton(onClick = onSync, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Teal)
            else Icon(Icons.Default.Refresh, "همگام‌سازی آهنگ‌ها", tint = Teal)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "تنظیمات کتابخانه", tint = Muted) }
    }
    Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 18.dp, end = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (busy) Color(0xFFDC9A38) else Teal))
        Spacer(Modifier.width(7.dp))
        Text(syncMessage, fontSize = 10.sp, color = Muted, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NavPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(13.dp), color = if (selected) Teal else Color.White, border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Row(Modifier.padding(vertical = 11.dp, horizontal = 7.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(17.dp), tint = if (selected) Color.White else Muted)
            Spacer(Modifier.width(5.dp))
            Text(label, color = if (selected) Color.White else Ink, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                    if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, color = Muted)
                }
            }
            content()
        }
    }
}

@Composable
private fun PlayerCard(song: Song?, playing: Boolean, preparing: Boolean, position: Int, duration: Int, looping: Boolean, volume: Float, onLoop: () -> Unit, onVolume: (Float) -> Unit, onToggle: () -> Unit, onSeek: (Int) -> Unit, onBack: () -> Unit, onForward: () -> Unit, onStop: () -> Unit, onSpeed: (Float) -> Unit) {
    SectionCard("پخش‌کنندهٔ قطعه", "گوش بدهید، همراه بخوانید و بخش‌های دشوار را تکرار کنید") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(Mint), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Headphones, null, tint = Teal, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: "هنوز آهنگی دریافت نشده", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 2)
                Text(listOfNotNull(song?.artist?.takeIf { it.isNotBlank() }, song?.album?.takeIf { it.isNotBlank() }, song?.year?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "از کتابخانهٔ درس" }, fontSize = 11.sp, color = Muted)
            }
            Surface(shape = RoundedCornerShape(20.dp), color = Mint) { Text(if (preparing) "آماده‌سازی" else if (playing) "در حال پخش" else "آماده", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 10.sp, color = Teal) }
        }
        if (song != null && (song.genre.isNotBlank() || song.note.isNotBlank())) {
            Text(listOfNotNull(song.genre.takeIf { it.isNotBlank() }, song.note.takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 12.sp, color = Muted)
        }
        Slider(value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f, onValueChange = { onSeek(it.toInt()) }, valueRange = 0f..max(1, duration).toFloat(), enabled = song != null && duration > 0, colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = Line))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position / 1000), fontSize = 11.sp, color = Muted)
            Text(formatTime(duration / 1000), fontSize = 11.sp, color = Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = song != null) { Text("−۵", fontWeight = FontWeight.SemiBold, color = Teal, fontSize = 14.sp) }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick = onToggle, enabled = song != null && !preparing, modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Teal)) {
                if (playing) Icon(Icons.Default.Pause, "مکث", modifier = Modifier.size(30.dp)) else Icon(Icons.Default.PlayArrow, "پخش", modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onForward, enabled = song != null) { Text("۵+", fontWeight = FontWeight.SemiBold, color = Teal, fontSize = 14.sp) }
            Spacer(Modifier.width(7.dp))
            IconButton(onClick = onStop, enabled = song != null) { Icon(Icons.Default.Stop, "توقف", tint = Muted) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onLoop, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (looping) Teal else Muted)) {
                Text(if (looping) "↻ تکرار روشن" else "↻ تکرار خاموش", fontSize = 11.sp)
            }
            Text("صدا", color = Muted, fontSize = 11.sp)
            Slider(value = volume, onValueChange = onVolume, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = Line))
            Text("${(volume * 100).toInt()}٪", color = Muted, fontSize = 10.sp)
        }
        var speedMenu by remember { mutableStateOf(false) }
        var speedLabel by remember { mutableStateOf("۱×") }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OutlinedButton(onClick = { speedMenu = true }, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)) { Text("سرعت پخش: $speedLabel", fontSize = 12.sp) }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                listOf("۰٫۵×" to 0.5f, "۰٫۷۵×" to 0.75f, "۱×" to 1f, "۱٫۲۵×" to 1.25f).forEach { (label, value) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { speedLabel = label; speedMenu = false; onSpeed(value) })
                }
            }
        }
    }
}

@Composable
private fun LyricsCard(text: String, positionSeconds: Double, onSeek: (Double) -> Unit) {
    val lines = remember(text) { parseLyrics(text) }
    val current = lines.indexOfLast { it.seconds != null && it.seconds <= positionSeconds + 0.15 }
    SectionCard("متن آهنگ", if (lines.any { it.seconds != null }) "برای رفتن به همان بخش، روی مصرع بزنید" else "متن تمرین") {
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            lines.forEachIndexed { index, line ->
                Text(
                    line.text.ifBlank { "♪" },
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = line.seconds != null) { line.seconds?.let(onSeek) }.background(if (index == current && line.seconds != null) Mint else Color.Transparent).padding(horizontal = 12.dp, vertical = 9.dp),
                    textAlign = TextAlign.Center, fontSize = if (index == current) 17.sp else 15.sp,
                    color = if (index == current && line.seconds != null) Teal else Muted,
                    fontWeight = if (index == current && line.seconds != null) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = 27.sp
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(songs: List<Song>, selectedId: String, busy: Boolean, onSelect: (String) -> Unit, onSync: () -> Unit, onSettings: () -> Unit) {
    SectionCard("کتابخانهٔ درس", "آهنگ‌ها با همگام‌سازی مربی به‌روز می‌شوند") {
        if (songs.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.LibraryMusic, null, tint = Teal, modifier = Modifier.size(38.dp))
                Text("کتابخانه هنوز خالی است", fontWeight = FontWeight.SemiBold, color = Ink)
                Text("آدرس GitHub را از تنظیمات وارد کنید.", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSettings, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("تنظیم مخزن") }
                    OutlinedButton(onClick = onSync, enabled = !busy) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("بررسی") }
                }
            }
        } else {
            songs.forEachIndexed { i, song ->
                if (i > 0) HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (song.id == selectedId) Mint else Color.Transparent).clickable { onSelect(song.id) }.padding(9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (song.id == selectedId) Teal else CanvasBg), contentAlignment = Alignment.Center) {
                        Icon(if (song.id == selectedId) Icons.Default.GraphicEq else Icons.Default.LibraryMusic, null, tint = if (song.id == selectedId) Color.White else Teal, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontSize = 13.sp, color = Ink, fontWeight = if (song.id == selectedId) FontWeight.Bold else FontWeight.Medium, maxLines = 2)
                        Text(song.artist.ifBlank { song.album.ifBlank { "آهنگ تمرینی" } }, fontSize = 10.sp, color = Muted, maxLines = 1)
                    }
                    Text("ذخیره‌شده", fontSize = 9.sp, color = Teal)
                }
            }
            OutlinedButton(onClick = onSync, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp)); Text("بررسی به‌روزرسانی‌ها")
            }
        }
    }
}

@Composable
private fun RecorderCard(recording: Boolean, elapsed: Long, onRecord: () -> Unit) {
    SectionCard("ضبط صدای تمرین", "صدای شما به‌صورت فایل MP3 روی همین گوشی ذخیره می‌شود") {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (recording) Color(0xFFFFF0EE) else Mint).padding(vertical = 23.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Default.Mic, null, tint = if (recording) Coral else Teal, modifier = Modifier.size(34.dp))
                Text(formatTime((elapsed / 1000).toInt()), fontSize = 41.sp, color = Ink, fontWeight = FontWeight.Medium)
                Text(if (recording) "در حال ضبط…" else "آماده برای ضبط", fontSize = 12.sp, color = Muted)
                WaveformDecor(recording)
            }
        }
        Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = if (recording) Coral else Teal)) {
            Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (recording) "پایان ضبط و ذخیرهٔ MP3" else "شروع ضبط")
        }
        Text("برای کیفیت بهتر، هنگام تمرین با آهنگ از هدفون استفاده کنید.", fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WaveformDecor(active: Boolean) {
    val bars = listOf(10, 19, 28, 15, 35, 23, 12, 27, 38, 18, 31, 15, 25, 10, 22, 34, 17, 26, 12, 20, 30, 14, 24, 37, 16)
    Row(Modifier.fillMaxWidth().height(43.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        bars.forEachIndexed { i, h -> Box(Modifier.padding(horizontal = 2.dp).width(3.dp).height((if (active) h else (h * .65).toInt()).dp).clip(CircleShape).background(if (active) Coral else Teal.copy(alpha = 0.5f))) }
    }
}

@Composable
private fun RecordingsCard(records: List<File>, onShare: (File) -> Unit, onPlay: (File) -> Unit, playingFileName: String) {
    SectionCard("ضبط‌های ذخیره‌شده", "فایل‌ها روی دستگاه می‌مانند و هر زمان قابل ارسال‌اند") {
        if (records.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, null, tint = Muted, modifier = Modifier.size(35.dp))
                Spacer(Modifier.height(7.dp)); Text("هنوز ضبطی ندارید", color = Muted, fontSize = 13.sp)
            }
        } else records.forEachIndexed { i, file ->
            if (i > 0) HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Mint), contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, null, tint = Teal) }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.nameWithoutExtension, color = Ink, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 2)
                    Text("${SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale("fa", "IR")).format(Date(file.lastModified()))}  ·  ${humanSize(file.length())}", color = Muted, fontSize = 10.sp)
                }
                IconButton(onClick = { onPlay(file) }) { Icon(Icons.Default.PlayArrow, "پخش ضبط", tint = Teal) }
                IconButton(onClick = { onShare(file) }) { Icon(Icons.Default.Share, "ارسال یا اشتراک‌گذاری", tint = Teal) }
            }
        }
        Text("برای فرستادن در تلگرام، دکمهٔ اشتراک‌گذاری را بزنید و Telegram را انتخاب کنید.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun TunerCard(active: Boolean, pitch: PitchReading?, reference: String, onReference: (String) -> Unit, onToggle: () -> Unit) {
    SectionCard("تیونر کروماتیک", "تشخیص زندهٔ نت برای تمرین آواز یا ساز") {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mint).padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pitch?.note ?: "—", fontSize = 66.sp, fontWeight = FontWeight.Medium, color = DeepTeal, lineHeight = 75.sp)
                Text(pitch?.let { "${"%.1f".format(Locale.US, it.hz)} Hz" } ?: if (active) "یک نت بخوانید…" else "برای شروع تیونر را روشن کنید", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(21.dp))
                Canvas(Modifier.fillMaxWidth().padding(horizontal = 17.dp).height(48.dp)) {
                    val center = size.width / 2
                    drawRoundRect(Line, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                    drawRect(Color(0xFFCBE8DE), topLeft = androidx.compose.ui.geometry.Offset(size.width * .45f, 0f), size = androidx.compose.ui.geometry.Size(size.width * .10f, size.height))
                    for (i in 0..20) {
                        val x = size.width * i / 20f
                        drawLine(Color(0xFFBCD0C8), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1.dp.toPx())
                    }
                    val cents = pitch?.cents?.coerceIn(-50, 50) ?: 0
                    val markerX = center + size.width * cents / 100f
                    drawLine(Teal, androidx.compose.ui.geometry.Offset(markerX, -3.dp.toPx()), androidx.compose.ui.geometry.Offset(markerX, size.height + 3.dp.toPx()), 4.dp.toPx())
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("−۵۰ سنت", color = Muted, fontSize = 10.sp); Text("دقیق", color = Teal, fontSize = 10.sp); Text("+۵۰ سنت", color = Muted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = if (pitch != null && abs(pitch.cents) < 5) Mint else if (pitch == null) Color(0xFFE8EEEB) else Color(0xFFFFF0EE)) {
                    Text(if (!active) "خاموش" else if (pitch == null) "در انتظار صدا" else if (abs(pitch.cents) < 5) "کوک است" else if (pitch.cents > 0) "${pitch.cents} سنت بالاتر" else "${-pitch.cents} سنت پایین‌تر", modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp), color = if (pitch != null && abs(pitch.cents) < 5) Teal else if (pitch != null) Coral else Muted, fontSize = 12.sp)
                }
            }
        }
        OutlinedTextField(value = reference, onValueChange = { v -> if (v.length <= 3 && v.all { it.isDigit() }) onReference(v) }, label = { Text("مرجع A4 بر حسب هرتز (۴۱۵ تا ۴۶۶)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = if (active) Coral else Teal)) {
            Icon(if (active) Icons.Default.Stop else Icons.Default.Tune, null); Spacer(Modifier.width(8.dp)); Text(if (active) "خاموش کردن تیونر" else "روشن کردن تیونر")
        }
        if ((reference.toIntOrNull() ?: 0) !in 415..466) Text("مرجع کوک باید بین ۴۱۵ و ۴۶۶ هرتز باشد.", color = Coral, fontSize = 11.sp)
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    SectionCard(title) { Text(body, fontSize = 13.sp, lineHeight = 22.sp, color = Muted) }
}

private fun formatTime(seconds: Int): String = String.format(Locale.US, "%02d:%02d", max(0, seconds) / 60, max(0, seconds) % 60)
private fun humanSize(bytes: Long): String = if (bytes < 1024 * 1024) "${max(1, bytes / 1024)} KB" else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
