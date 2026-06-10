package app.werkbank.shared.tunnel

import kotlinx.serialization.json.Json

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = false
}