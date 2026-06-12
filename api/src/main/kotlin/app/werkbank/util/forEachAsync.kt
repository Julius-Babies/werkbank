package app.werkbank.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

context(scope: CoroutineScope)
suspend inline fun <T> Iterable<T>.forEachAsync(crossinline block: suspend (T) -> Unit) {
    this.map { scope.async { block(it) } }.forEach { it.await() }
}