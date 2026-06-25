package http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

actual fun httpClientBase(configure: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient().config(configure)
}

actual fun Exception.isTimeoutException(): Boolean {
    TODO("Not yet implemented")
}