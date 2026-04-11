package com.ernela.allohaparser

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.ernela.allohaparser.ui.theme.AllohaParserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TranslationInfo(val id: String, val name: String, val iframeUrl: String)
data class EpisodeInfo(val num: String, val translations: List<TranslationInfo>)
data class SeasonInfo(val num: String, val episodes: List<EpisodeInfo>)
data class AllohaApiResult(
    val title: String,
    val isSerial: Boolean,
    val movieIframe: String?,
    val seasons: List<SeasonInfo>
)

class MainActivity : ComponentActivity() {
    var exoPlayer: ExoPlayer? = null
    var parser: AllohaParser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parser = AllohaParser(this)

        enableEdgeToEdge()
        setContent {
            AllohaParserTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        ParserScreen(
                            modifier = Modifier.padding(innerPadding),
                            activity = this,
                            parser = parser!!
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        parser?.release()
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ParserScreen(modifier: Modifier = Modifier, activity: MainActivity, parser: AllohaParser) {
    val coroutineScope = rememberCoroutineScope()

    var tokenInput by remember { mutableStateOf("ffbd312217e27c4245f2678afe1881") }
    var kpInput by remember { mutableStateOf("464963") }
    var statusText by remember { mutableStateOf("Ready") }

    var apiResult by remember { mutableStateOf<AllohaApiResult?>(null) }
    var selectedSeason by remember { mutableStateOf<SeasonInfo?>(null) }
    var selectedEpisode by remember { mutableStateOf<EpisodeInfo?>(null) }
    var selectedTranslation by remember { mutableStateOf<TranslationInfo?>(null) }

    var availableQualities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var currentIframeBase by remember { mutableStateOf("") }
    var showPlayer by remember { mutableStateOf(false) }

    val activeHeaders = remember { ConcurrentHashMap<String, String>() }
    var currentDataSourceFactory by remember { mutableStateOf<OkHttpDataSource.Factory?>(null) }
    var currentM3u8Url by remember { mutableStateOf("") }
    var fallbackM3u8Url by remember { mutableStateOf("") }
    var configUpdateReceived by remember { mutableStateOf(false) }

    fun switchMediaSource(m3u8Url: String) {
        val factory = currentDataSourceFactory ?: return
        val player = activity.exoPlayer ?: return
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady

        val mediaItem = MediaItem.Builder()
            .setUri(m3u8Url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val hlsSource = HlsMediaSource.Factory(factory).createMediaSource(mediaItem)

        player.setMediaSource(hlsSource, position)
        player.prepare()
        player.playWhenReady = wasPlaying
        Log.d("AllohaPlayer", "MediaSource switched to $m3u8Url at ${position}ms")
    }

    fun buildDataSourceFactory(iframeOrigin: String, traceContext: Context): OkHttpDataSource.Factory {
        val appCtx = traceContext.applicationContext
        val defaultReferer = "$iframeOrigin/"
        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
        )
        var uaIndex = 0
        val fallbackUa = userAgents[0]
        val cookieManager = CookieManager.getInstance()

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(5, 30, java.util.concurrent.TimeUnit.SECONDS))
            .addNetworkInterceptor(Interceptor { chain ->
                val currentHeaders = activeHeaders.toMap()
                val requestUrl = chain.request().url.toString()
                val requestBuilder = chain.request().newBuilder()

                val streamOrigin = currentHeaders["origin"]?.takeIf { it.isNotBlank() } ?: iframeOrigin
                val streamReferer = currentHeaders["referer"]?.takeIf { it.isNotBlank() } ?: defaultReferer
                val userAgent = currentHeaders["user-agent"]?.takeIf { it.isNotBlank() } ?: fallbackUa

                requestBuilder.header("Origin", streamOrigin)
                requestBuilder.header("Referer", streamReferer)
                requestBuilder.header("User-Agent", userAgent)
                // Prevent stale keep-alive connections to CDN after pause
                if (requestUrl.contains("stream-balancer")) {
                    requestBuilder.header("Connection", "close")
                }
                if (chain.request().header("Accept") == null) {
                    requestBuilder.header("Accept", currentHeaders["accept"] ?: "*/*")
                }

                val allowed = setOf(
                    "accept", "accept-encoding", "accept-language",
                    "authorizations", "accepts-controls", "origin", "referer",
                    "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
                    "sec-gpc", "dnt", "user-agent", "cookie", "range"
                )
                currentHeaders.forEach { (k, v) ->
                    val lk = k.lowercase(Locale.ROOT)
                    if (lk in allowed && lk !in setOf("accept", "origin", "referer", "user-agent", "cookie")) {
                        if (lk == "authorizations" || lk == "accepts-controls") {
                            requestBuilder.header(lk, v)
                        } else {
                            val titleCase = k.split("-").joinToString("-") { part ->
                                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                            }
                            requestBuilder.header(titleCase, v)
                        }
                    }
                }

                val cookie = sequenceOf(requestUrl, streamOrigin, streamReferer)
                    .mapNotNull { c -> cookieManager.getCookie(c)?.takeIf { it.isNotBlank() } }
                    .firstOrNull()
                if (!cookie.isNullOrBlank()) requestBuilder.header("Cookie", cookie)

                val req = requestBuilder.build()
                try {
                    val response = chain.proceed(req)
                    // If 403 on CDN — rotate UA and retry once
                    if (response.code == 403 && req.url.toString().contains("stream-balancer")) {
                        response.close()
                        uaIndex = (uaIndex + 1) % userAgents.size
                        val newUa = userAgents[uaIndex]
                        Log.d("AllohaPlayer", "403 on CDN, rotating UA to: ${newUa.take(60)}")
                        val retryReq = req.newBuilder().header("User-Agent", newUa).build()
                        val retryResp = chain.proceed(retryReq)
                        AllohaHttpTrace.logOkHttpRoundTrip(appCtx, retryReq, retryResp, null)
                        return@Interceptor retryResp
                    }
                    AllohaHttpTrace.logOkHttpRoundTrip(appCtx, req, response, null)
                    response
                } catch (e: Exception) {
                    AllohaHttpTrace.logOkHttpRoundTrip(
                        appCtx,
                        req,
                        null,
                        e.message ?: e.javaClass.simpleName,
                    )
                    throw e
                }
            })
            .build()

        return OkHttpDataSource.Factory(client).setUserAgent(fallbackUa)
    }

    fun parseStream(iframe: String) {
        statusText = "Capturing stream session..."
        availableQualities = emptyMap()
        showPlayer = false
        fallbackM3u8Url = ""
        activity.exoPlayer?.stop()

        val parsedUrl = URL(iframe)
        currentIframeBase = "${parsedUrl.protocol}://${parsedUrl.host.lowercase(Locale.ROOT)}"

        AllohaHttpTrace.reset(activity.applicationContext)
        configUpdateReceived = false
        currentDataSourceFactory = buildDataSourceFactory(currentIframeBase, activity.applicationContext)

        parser.parse(iframe, object : AllohaParser.Callback {
            override fun onHlsLinksReceived(json: String, extraHeaders: Map<String, String>) {
                try {
                    val jsonObj = JSONObject(json)
                    val hlsSource = jsonObj.optJSONArray("hlsSource")
                        ?: throw IllegalStateException("No hlsSource")

                    val qualitiesMap = mutableMapOf<String, String>()
                    fallbackM3u8Url = ""
                    for (i in 0 until hlsSource.length()) {
                        val qualityObj = hlsSource.getJSONObject(i).optJSONObject("quality") ?: continue
                        qualityObj.keys().forEach { q ->
                            val parts = qualityObj.optString(q, "").split(" or ")
                            val link = parts[0].trim()
                            if (link.isNotBlank()) {
                                qualitiesMap[q] = if (link.startsWith("//")) "https:$link" else link
                            }
                            // Save fallback from first quality entry that has a second URL
                            if (fallbackM3u8Url.isBlank() && parts.size > 1) {
                                val fb = parts[1].trim()
                                if (fb.isNotBlank()) fallbackM3u8Url = if (fb.startsWith("//")) "https:$fb" else fb
                            }
                        }
                    }
                    if (qualitiesMap.isEmpty()) throw IllegalStateException("No qualities found")

                    activeHeaders.clear()
                    activeHeaders.putAll(extraHeaders)
                    AllohaHttpTrace.logJsHeaders(activity.applicationContext, "onReady", extraHeaders)

                    availableQualities = qualitiesMap
                    statusText = "Stream captured! Select quality to play."
                } catch (e: Exception) {
                    statusText = "Parse Error: ${e.message}"
                }
            }

            override fun onConfigUpdate(edgeHash: String, ttlSeconds: Int, extraHeaders: Map<String, String>) {
                activeHeaders.putAll(extraHeaders)
                AllohaHttpTrace.logJsHeaders(activity.applicationContext, "config_update", extraHeaders)
                Log.d("AllohaPlayer", "config_update: headers updated, edge_hash=$edgeHash TTL=${ttlSeconds}s")
                statusText = "Stream refreshed (TTL=${ttlSeconds}s)"
                // First config_update after session start — now safe to play
                if (!configUpdateReceived) {
                    configUpdateReceived = true
                    val url = currentM3u8Url
                    if (url.isNotBlank() && activity.exoPlayer != null && showPlayer) {
                        switchMediaSource(url)
                    }
                }
            }

            override fun onM3u8Refreshed(url: String, extraHeaders: Map<String, String>) {
                activeHeaders.putAll(extraHeaders)
                AllohaHttpTrace.logJsHeaders(activity.applicationContext, "m3u8_refresh", extraHeaders)
                val prevUrl = currentM3u8Url
                currentM3u8Url = url
                Log.d("AllohaPlayer", "m3u8 refreshed: $url")

                if (activity.exoPlayer != null && showPlayer) {
                    val prevHost = prevUrl.substringAfter("://").substringBefore("/")
                    val newHost = url.substringAfter("://").substringBefore("/")
                    val hostChanged = prevUrl.isNotBlank() && prevHost != newHost
                    if (hostChanged) {
                        // CDN node switched — reset and wait for new config_update with correct hash
                        configUpdateReceived = false
                        Log.d("AllohaPlayer", "CDN host changed $prevHost -> $newHost, waiting for config_update")
                        statusText = "CDN failover, waiting for auth..."
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(10000)
                            if (!configUpdateReceived && currentM3u8Url == url && showPlayer && activity.exoPlayer != null) {
                                Log.d("AllohaPlayer", "config_update timeout after failover, starting anyway")
                                configUpdateReceived = true
                                switchMediaSource(url)
                            }
                        }
                    } else if (configUpdateReceived) {
                        switchMediaSource(url)
                        statusText = "Stream URL refreshed"
                    } else {
                        // Waiting for config_update — but if it never comes, start after timeout
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(10000)
                            if (!configUpdateReceived && currentM3u8Url == url && showPlayer && activity.exoPlayer != null) {
                                Log.d("AllohaPlayer", "config_update timeout, starting with initial accepts-controls")
                                configUpdateReceived = true
                                switchMediaSource(url)
                            }
                        }
                    }
                }
            }

            override fun onStreamHeadersUpdated(extraHeaders: Map<String, String>) {
                activeHeaders.putAll(extraHeaders)
                AllohaHttpTrace.logJsHeaders(activity.applicationContext, "stream_push", extraHeaders)
            }

            override fun onError(error: String) {
                statusText = "Error: $error"
            }
        })
    }

    fun playQuality(m3u8Url: String, qualityKey: String) {
        try {
            val factory = currentDataSourceFactory ?: run {
                statusText = "Error: no session, press Get Links first"
                return
            }

            // Prefer the refreshed master.m3u8 URL over the stale bnsi quality URL.
            // After config_update, the bnsi quality URLs have expired signatures and
            // will return 403. The refreshed URL from onM3u8Refreshed has valid sigs.
            // ExoPlayer handles adaptive quality switching from the master.m3u8.
            val urlToPlay = if (currentM3u8Url.isNotBlank()) {
                Log.d("AllohaPlayer", "Using refreshed master.m3u8 instead of bnsi quality URL")
                currentM3u8Url
            } else {
                currentM3u8Url = m3u8Url
                m3u8Url
            }

            val trackSelector = DefaultTrackSelector(activity).apply {
                setParameters(
                    buildUponParameters()
                        .setPreferredAudioLanguage("ru")
                        .setPreferredTextLanguage("ru")
                        .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                        .setRendererDisabled(/* audio renderer index */ 1, false)
                )
            }

            // After player is built, override audio track to first rendition (Russian)
            val trackOverrideListener = object : androidx.media3.common.Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    val audioGroups = tracks.groups.filter { group ->
                        group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO
                    }
                    if (audioGroups.size > 1) {
                        val firstGroup = audioGroups[0]
                        val override = androidx.media3.common.TrackSelectionOverride(
                            firstGroup.mediaTrackGroup, /* trackIndex */ 0
                        )
                        trackSelector.setParameters(
                            trackSelector.buildUponParameters()
                                .setOverrideForType(override)
                                .build()
                        )
                        Log.d("AllohaPlayer", "Audio track overridden to first group (Russian)")
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val fb = fallbackM3u8Url
                    if (fb.isNotBlank() && currentM3u8Url != fb) {
                        Log.d("AllohaPlayer", "CDN error, will switch to fallback when onM3u8Refreshed fires: $fb")
                        statusText = "CDN error, waiting for backup node..."
                        configUpdateReceived = false
                        // Don't switch yet — onM3u8Refreshed will fire with 35d-a13 URL and handle it
                    } else {
                        statusText = "Playback error: ${error.message}"
                    }
                }
            }

            val player = activity.exoPlayer ?: ExoPlayer.Builder(activity)
                .setTrackSelector(trackSelector)
                .build()
                .also {
                    activity.exoPlayer = it
                    it.addListener(trackOverrideListener)
                }

            val mediaItem = MediaItem.Builder()
                .setUri(urlToPlay)  // was: .setUri(m3u8Url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            val hlsSource = HlsMediaSource.Factory(factory).createMediaSource(mediaItem)

            player.stop()
            player.clearMediaItems()
            player.setMediaSource(hlsSource)
            player.prepare()
            player.playWhenReady = true

            statusText = "Playing ${qualityKey}p..."
            showPlayer = true
        } catch (e: Exception) {
            statusText = "Play Error: ${e.message}"
        }
    }

    // UI
    Column(modifier = modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {

        Box(modifier = Modifier.size(10.dp).alpha(0.01f)) {
            AndroidView(factory = { parser.webView })
        }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Alloha Token") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = kpInput,
            onValueChange = { kpInput = it.filter { ch -> ch.isDigit() } },
            label = { Text("KP ID") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (tokenInput.isBlank() || kpInput.isBlank()) return@Button
                coroutineScope.launch {
                    statusText = "Fetching API data..."
                    apiResult = null
                    availableQualities = emptyMap()
                    showPlayer = false
                    activity.exoPlayer?.stop()

                    try {
                        val encodedToken = URLEncoder.encode(tokenInput.trim(), "UTF-8")
                        val encodedKp = URLEncoder.encode(kpInput.trim(), "UTF-8")
                        val apiUrl = "https://api.alloha.tv/?token=$encodedToken&kp=$encodedKp"

                        val jsonStr = withContext(Dispatchers.IO) {
                            val connection = URL(apiUrl).openConnection() as HttpsURLConnection
                            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                                override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                                override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                            })
                            val sc = SSLContext.getInstance("TLS")
                            sc.init(null, trustAllCerts, SecureRandom())
                            connection.sslSocketFactory = sc.socketFactory
                            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
                            connection.requestMethod = "GET"
                            connection.inputStream.bufferedReader().readText()
                        }

                        val dataObj = JSONObject(jsonStr).getJSONObject("data")
                        val title = dataObj.optString("name", "Unknown")
                        val seasonsObj = dataObj.optJSONObject("seasons")

                        if (seasonsObj != null) {
                            val parsedSeasons = mutableListOf<SeasonInfo>()
                            seasonsObj.keys().forEach { sKey ->
                                val sObj = seasonsObj.getJSONObject(sKey)
                                val episodesObj = sObj.optJSONObject("episodes") ?: return@forEach
                                val parsedEpisodes = mutableListOf<EpisodeInfo>()

                                episodesObj.keys().forEach { eKey ->
                                    val eObj = episodesObj.getJSONObject(eKey)
                                    val transObj = eObj.optJSONObject("translation") ?: return@forEach
                                    val parsedTrans = mutableListOf<TranslationInfo>()

                                    transObj.keys().forEach { tKey ->
                                        val tData = transObj.getJSONObject(tKey)
                                        parsedTrans.add(
                                            TranslationInfo(
                                                id = tKey,
                                                name = tData.optString("translation", "Unknown"),
                                                iframeUrl = tData.optString("iframe")
                                            )
                                        )
                                    }
                                    parsedEpisodes.add(EpisodeInfo(eKey, parsedTrans.sortedBy { it.name }))
                                }
                                parsedSeasons.add(SeasonInfo(sKey, parsedEpisodes.sortedBy { it.num.toIntOrNull() ?: 0 }))
                            }

                            val sortedSeasons = parsedSeasons.sortedBy { it.num.toIntOrNull() ?: 0 }
                            apiResult = AllohaApiResult(title, true, null, sortedSeasons)
                            selectedSeason = sortedSeasons.firstOrNull()
                            selectedEpisode = selectedSeason?.episodes?.firstOrNull()
                            selectedTranslation = selectedEpisode?.translations?.firstOrNull()
                            statusText = "Series loaded. Select episode."
                        } else {
                            val iframe = dataObj.getString("iframe")
                            apiResult = AllohaApiResult(title, false, iframe, emptyList())
                            statusText = "Movie loaded. Click Get Links."
                        }
                    } catch (e: Exception) {
                        statusText = "API Error: ${e.message}"
                    }
                }
            }
        ) {
            Text("Fetch Info", color = MaterialTheme.colorScheme.onPrimary)
        }

        Spacer(modifier = Modifier.height(16.dp))

        apiResult?.let { res ->
            Text("Title: ${res.title}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))

            if (res.isSerial) {
                var sExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = sExpanded, onExpandedChange = { sExpanded = it }) {
                    @Suppress("DEPRECATION")
                    OutlinedTextField(
                        value = "Season ${selectedSeason?.num ?: ""}",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Season") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(expanded = sExpanded, onDismissRequest = { sExpanded = false }) {
                        res.seasons.forEach { s ->
                            DropdownMenuItem(
                                text = { Text("Season ${s.num}") },
                                onClick = {
                                    selectedSeason = s
                                    selectedEpisode = s.episodes.firstOrNull()
                                    selectedTranslation = selectedEpisode?.translations?.firstOrNull()
                                    sExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                var eExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = eExpanded, onExpandedChange = { eExpanded = it }) {
                    @Suppress("DEPRECATION")
                    OutlinedTextField(
                        value = "Episode ${selectedEpisode?.num ?: ""}",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Episode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = eExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(expanded = eExpanded, onDismissRequest = { eExpanded = false }) {
                        selectedSeason?.episodes?.forEach { e ->
                            DropdownMenuItem(
                                text = { Text("Episode ${e.num}") },
                                onClick = {
                                    selectedEpisode = e
                                    selectedTranslation = e.translations.firstOrNull()
                                    eExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                var tExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = tExpanded, onExpandedChange = { tExpanded = it }) {
                    @Suppress("DEPRECATION")
                    OutlinedTextField(
                        value = selectedTranslation?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Voice / Translation") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(expanded = tExpanded, onDismissRequest = { tExpanded = false }) {
                        selectedEpisode?.translations?.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name) },
                                onClick = {
                                    selectedTranslation = t
                                    tExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedTranslation?.iframeUrl?.let { parseStream(it) } }
                ) { Text("Get Links", color = MaterialTheme.colorScheme.onPrimary) }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { res.movieIframe?.let { parseStream(it) } }
                ) { Text("Get Links", color = MaterialTheme.colorScheme.onPrimary) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = statusText, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        if (availableQualities.isNotEmpty()) {
            Text("Select Quality:", color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            @kotlin.OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("2160", "1440", "1080", "720", "480", "360").forEach { q ->
                    availableQualities[q]?.let { url ->
                        Button(onClick = { playQuality(url, q) }) {
                            Text("${q}p", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showPlayer) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = true
                        keepScreenOn = true
                        player = activity.exoPlayer
                    }
                },
                update = { view -> view.player = activity.exoPlayer },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        }

        Spacer(modifier = Modifier.height(50.dp))
    }
}
