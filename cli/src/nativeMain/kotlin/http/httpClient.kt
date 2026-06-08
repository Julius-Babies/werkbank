package http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun httpClientBase(configure: HttpClientConfig<*>.() -> Unit): HttpClient

fun httpClient(): HttpClient {
    return httpClientBase {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
}
