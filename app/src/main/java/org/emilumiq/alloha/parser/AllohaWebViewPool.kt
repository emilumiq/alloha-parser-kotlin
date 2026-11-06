package org.emilumiq.alloha.parser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue

private val AD_HOST_BLOCKLIST = setOf(
    "doubleclick.net",
    "googlesyndication.com",
    "adnxs.com",
    "criteo.com",
    "taboola.com",
    "outbrain.com",
    "popads.net",
    "propellerads.com",
    "adsterra.com",
    "exoclick.com",
    "juicyads.com",
    "mgid.com",
)

private class AdBlockingWebViewClient : WebViewClient() {
    private val blockedResponse by lazy {
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val host = request.url.host ?: return null
        val blocked = AD_HOST_BLOCKLIST.any { blockedHost ->
            host == blockedHost || host.endsWith(".$blockedHost")
        }
        return if (blocked) blockedResponse else null
    }
}

internal class AllohaWebViewPool {
    private val idleWebViews = ConcurrentLinkedQueue<WebView>()

    fun acquire(context: Context, userAgent: String): WebView {
        val webView = idleWebViews.poll() ?: createWebView(context)
        webView.settings.userAgentString = userAgent
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        return webView
    }

    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webViewClient = AdBlockingWebViewClient()
    }

    fun resetIdentity() {
        generateSequence { idleWebViews.poll() }.forEach { it.destroy() }
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    fun release(webView: WebView) {
        if (idleWebViews.size >= MAX_IDLE_WEB_VIEWS) {
            webView.destroy()
        } else {
            idleWebViews.offer(webView)
        }
    }

    fun destroy() {
        generateSequence { idleWebViews.poll() }.forEach { it.destroy() }
    }

    private companion object {
        const val MAX_IDLE_WEB_VIEWS = 1
    }
}
