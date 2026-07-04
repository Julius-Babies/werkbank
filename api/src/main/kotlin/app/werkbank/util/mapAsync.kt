package app.werkbank.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

context(scope: CoroutineScope)
suspend inline fun <T, R> Iterable<T>.mapAsync(crossinline transform: suspend (T) -> R): List<R> {
    return this.map { scope.async { transform(it) } }.awaitAll()
}