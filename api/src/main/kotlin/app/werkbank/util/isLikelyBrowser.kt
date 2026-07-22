package app.werkbank.util

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

fun ApplicationCall.isLikelyBrowser(): Boolean {
    val headers = request.headers

    val fetchMode = headers["Sec-Fetch-Mode"]
    val isFetchMetadataPresent = fetchMode != null

    val userAgent = headers[HttpHeaders.UserAgent] ?: ""
    val hasBrowserUserAgent = listOf("Mozilla/", "Chrome/", "Safari/", "Firefox/", "Edge/")
        .any { userAgent.contains(it, ignoreCase = true) }

    val acceptsHtml = headers[HttpHeaders.Accept]?.contains("text/html") == true

    return isFetchMetadataPresent || (hasBrowserUserAgent && acceptsHtml)
}