package http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.SocketTimeoutException
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut

actual fun httpClientBase(configure: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(Darwin).config(configure)
}

actual fun Exception.isTimeoutException(): Boolean {
    if (this is SocketTimeoutException) return true
    if (this !is DarwinHttpRequestException) return false
    return this.origin.code == NSURLErrorTimedOut
}

actual fun Exception.isServiceNotRunningException(): Boolean {
    if (this !is DarwinHttpRequestException) return false
    return this.origin.domain == NSURLErrorDomain
}