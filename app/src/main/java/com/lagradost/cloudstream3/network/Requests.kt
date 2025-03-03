package com.lagradost.cloudstream3.network

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import java.io.File
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit

var baseClient = OkHttpClient()
private const val DEFAULT_TIME = 10
private val DEFAULT_TIME_UNIT = TimeUnit.MINUTES
private const val DEFAULT_USER_AGENT = USER_AGENT
private val DEFAULT_HEADERS = mapOf("User-Agent" to DEFAULT_USER_AGENT)
private val DEFAULT_DATA: Map<String, String> = mapOf()
private val DEFAULT_COOKIES: Map<String, String> = mapOf()
private val DEFAULT_REFERER: String? = null

fun Context.initRequestClient(): OkHttpClient {
    val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
    val dns = settingsManager.getInt(this.getString(R.string.dns_pref), 0)
    baseClient = OkHttpClient.Builder()
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            Cache(
                directory = File(cacheDir, "http_cache"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )
        ).apply {
            when (dns) {
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
            }
        }
        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

/** WARNING! CAN ONLY BE READ ONCE */
val Response.text: String
    get() {
        return this.body?.string() ?: ""
    }

val Response.url: String
    get() {
        return this.request.url.toString()
    }

val Response.cookies: Map<String, String>
    get() {
        val cookieList =
            this.headers.filter { it.first.toLowerCase(Locale.ROOT) == "set-cookie" }.getOrNull(0)?.second?.split(";")
        return cookieList?.associate {
            val split = it.split("=")
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }?.filter { it.key.isNotBlank() && it.value.isNotBlank() } ?: mapOf()
    }

fun getData(data: Map<String, String>): RequestBody {
    val builder = FormBody.Builder()
    data.forEach {
        builder.add(it.key, it.value)
    }
    return builder.build()
}

// https://github.com, id=test -> https://github.com?id=test
fun appendUri(uri: String, appendQuery: String): String {
    val oldUri = URI(uri)
    return URI(
        oldUri.scheme, oldUri.authority, oldUri.path,
        if (oldUri.query == null) appendQuery else oldUri.query + "&" + appendQuery, oldUri.fragment
    ).toString()
}

// Can probably be done recursively
fun addParamsToUrl(url: String, params: Map<String, String>): String {
    var appendedUrl = url
    params.forEach {
        appendedUrl = appendUri(appendedUrl, "${it.key}=${it.value}")
    }
    return appendedUrl
}

fun getCache(cacheTime: Int, cacheUnit: TimeUnit): CacheControl {
    return CacheControl.Builder().maxAge(cacheTime, cacheUnit).build()
}

/**
 * Referer > Set headers > Set cookies > Default headers > Default Cookies
 */
fun getHeaders(headers: Map<String, String>, referer: String?, cookie: Map<String, String>): Headers {
    val refererMap = (referer ?: DEFAULT_REFERER)?.let { mapOf("referer" to it) } ?: mapOf()
    val cookieHeaders = (DEFAULT_COOKIES + cookie)
    val cookieMap =
        if (cookieHeaders.isNotEmpty()) mapOf("Cookie" to cookieHeaders.entries.joinToString(separator = "; ") {
            "${it.key}=${it.value};"
        }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + cookieMap + headers + refererMap)
    return tempHeaders.toHeaders()
}

fun get(
    url: String,
    headers: Map<String, String> = mapOf(),
    referer: String? = null,
    params: Map<String, String> = mapOf(),
    cookies: Map<String, String> = mapOf(),
    allowRedirects: Boolean = true,
    cacheTime: Int = DEFAULT_TIME,
    cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
    timeout: Long = 0L,
    interceptor: Interceptor? = null
): Response {
    val client = baseClient
        .newBuilder()
        .followRedirects(allowRedirects)
        .followSslRedirects(allowRedirects)
        .callTimeout(timeout, TimeUnit.SECONDS)

    if (interceptor != null) client.addInterceptor(interceptor)
    val request = getRequestCreator(url, headers, referer, params, cookies, cacheTime, cacheUnit)
    return client.build().newCall(request).execute()
}


fun post(
    url: String,
    headers: Map<String, String> = mapOf(),
    referer: String? = null,
    params: Map<String, String> = mapOf(),
    cookies: Map<String, String> = mapOf(),
    data: Map<String, String> = DEFAULT_DATA,
    allowRedirects: Boolean = true,
    cacheTime: Int = DEFAULT_TIME,
    cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
    timeout: Long = 0L
): Response {
    val client = baseClient
        .newBuilder()
        .followRedirects(allowRedirects)
        .followSslRedirects(allowRedirects)
        .callTimeout(timeout, TimeUnit.SECONDS)
        .build()
    val request = postRequestCreator(url, headers, referer, params, cookies, data, cacheTime, cacheUnit)
    return client.newCall(request).execute()
}


fun getRequestCreator(
    url: String,
    headers: Map<String, String>,
    referer: String?,
    params: Map<String, String>,
    cookies: Map<String, String>,
    cacheTime: Int,
    cacheUnit: TimeUnit
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params))
        .cacheControl(getCache(cacheTime, cacheUnit))
        .headers(getHeaders(headers, referer, cookies))
        .build()
}

fun postRequestCreator(
    url: String,
    headers: Map<String, String>,
    referer: String?,
    params: Map<String, String>,
    cookies: Map<String, String>,
    data: Map<String, String>,
    cacheTime: Int,
    cacheUnit: TimeUnit
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params))
        .cacheControl(getCache(cacheTime, cacheUnit))
        .headers(getHeaders(headers, referer, cookies))
        .post(getData(data))
        .build()
}
