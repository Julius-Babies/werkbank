package util

import com.kgit2.kommand.io.Output

fun Output.throwError(message: String) {
    throw RuntimeException(
        """$message
                |Status: ${this.status}
                |Output: ${this.stdout}
                |Error: ${this.stderr}
                """.trimMargin()
    )
}