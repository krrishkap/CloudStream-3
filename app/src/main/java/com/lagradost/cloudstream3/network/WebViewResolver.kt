package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class WebViewResolver(val interceptUrl: Regex) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runBlocking {
            val fixedRequest = resolveUsingWebView(request)
            return@runBlocking chain.proceed(fixedRequest ?: request)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveUsingWebView(request: Request): Request? {
        val url = request.url.toString()
        val headers = request.headers
        println("Initial web-view request: $url")
        var webView: WebView? = null

        fun destroyWebView() {
            main {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                println("Destroyed webview")
            }
        }

        var fixedRequest: Request? = null

        main {
            // Useful for debugging
//            WebView.setWebContentsDebuggingEnabled(true)
            webView = WebView(
                AcraApplication.context ?: throw RuntimeException("No base context in WebViewResolver")
            ).apply {
                // Bare minimum to bypass captcha
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }

            webView?.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val webViewUrl = request.url.toString()

                    if (interceptUrl.containsMatchIn(webViewUrl)) {
                        fixedRequest = getRequestCreator(
                            webViewUrl,
                            request.requestHeaders,
                            null,
                            mapOf(),
                            mapOf(),
                            10,
                            TimeUnit.MINUTES
                        )

                        println("Web-view request finished: $webViewUrl")
                        destroyWebView()
                    }

                    return try {
                        when {
                            // suppress favicon requests as we don't display them anywhere
                            webViewUrl.endsWith("/favicon.ico") -> WebResourceResponse("image/png", null, null)
                            webViewUrl.contains("recaptcha") -> super.shouldInterceptRequest(view, request)

                            request.method == "GET" -> get(
                                webViewUrl,
                                headers = request.requestHeaders
                            ).toWebResourceResponse()

                            request.method == "POST" -> post(
                                webViewUrl,
                                headers = request.requestHeaders
                            ).toWebResourceResponse()
                            else -> return super.shouldInterceptRequest(view, request)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed() // Ignore ssl issues
                }
            }
            webView?.loadUrl(url, headers.toMap())
        }

        var loop = 0
        // Timeouts after this amount, 60s
        val totalTime = 60000L

        val delayTime = 100L

        // A bit sloppy, but couldn't find a better way
        while (loop < totalTime / delayTime) {
            if (fixedRequest != null) return fixedRequest
            delay(delayTime)
            loop += 1
        }

        println("Web-view timeout after ${totalTime / 1000}s")
        destroyWebView()
        return null
    }

    fun Response.toWebResourceResponse(): WebResourceResponse {
        val contentTypeValue = this.header("Content-Type")
        // 1. contentType. 2. charset
        val typeRegex = Regex("""(.*);(?:.*charset=(.*)(?:|;)|)""")
        return if (contentTypeValue != null) {
            val found = typeRegex.find(contentTypeValue ?: "")
            val contentType = found?.groupValues?.getOrNull(1)?.ifBlank { null } ?: contentTypeValue
            val charset = found?.groupValues?.getOrNull(2)?.ifBlank { null }
            WebResourceResponse(contentType, charset, this.body?.byteStream())
        } else {
            WebResourceResponse("application/octet-stream", null, this.body?.byteStream())
        }
    }
}