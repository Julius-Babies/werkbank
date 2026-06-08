package http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

actual fun httpClientBase(configure: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(Darwin).config(configure)
}