package org.emilumiq.alloha.parser

import android.os.Handler
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AllohaSessionBridge(
    private val handler: Handler,
    private val scope: CoroutineScope,
    private val sourcesSink: (AllohaParsedSources, Map<String, String>) -> Unit,
    private val sourcesFailureSink: (Throwable) -> Unit,
    private val configUpdateSink: (edgeHash: String, ttlSeconds: Int, headers: Map<String, String>) -> Unit,
    private val masterSink: (url: String, headers: Map<String, String>) -> Unit,
    private val streamHeadersSink: (Map<String, String>) -> Unit,
    private val dubbingUnavailableSink: () -> Unit,
    private val logSink: (String) -> Unit,
) {
    @JavascriptInterface
    fun onReady(responseJson: String, headersJson: String) {
        scope.launch {
            val result = runCatching {
                Pair(AllohaSourceParser.parse(responseJson), parseHeaders(headersJson))
            }
            handler.post {
                result.fold(
                    onSuccess = { (sources, headers) -> sourcesSink(sources, headers) },
                    onFailure = sourcesFailureSink,
                )
            }
        }
    }

    @JavascriptInterface
    fun onConfigUpdate(edgeHash: String, ttlSeconds: Int, headersJson: String) {
        val headers = parseHeaders(headersJson)
        handler.post { configUpdateSink(edgeHash, ttlSeconds, headers) }
    }

    @JavascriptInterface
    fun onM3u8Refreshed(url: String, headersJson: String) {
        val headers = parseHeaders(headersJson)
        handler.post { masterSink(url.normalizeStreamUrl(), headers) }
    }

    @JavascriptInterface
    fun onStreamHeaders(headersJson: String) {
        val headers = parseHeaders(headersJson)
        handler.post { streamHeadersSink(headers) }
    }

    @JavascriptInterface
    fun onDubbingUnavailable() {
        handler.post(dubbingUnavailableSink)
    }

    @JavascriptInterface
    fun onLog(message: String) {
        logSink(message)
    }
}
