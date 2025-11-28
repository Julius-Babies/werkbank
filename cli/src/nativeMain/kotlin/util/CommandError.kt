package util

import com.kgit2.kommand.io.Output
import kotlin.system.exitProcess

fun Output.throwError(message: String, command: String, args: List<String>) {
    println(buildStyledString {
        red {
            bold { +"A fatal error occurred and the program must stop!" }
        }
    })
    println(buildStyledString {
        red { +message }
    })
    println(buildStyledString {
        yellow { +"A command (CLI) call has failed." }
    })
    println(buildStyledString {
        +"$ "
        bold { +command }
        +" "
        +args.joinToString(" ")
    })
    println(buildStyledString {
        aqua { +"Status: " }
        gray { +this@throwError.status.toString() }
    })
    println(buildStyledString {
        aqua { +"Stdout:" }
    })
    println(buildStyledString { gray { +this@throwError.stdout.toString() } })
    println(buildStyledString {
        aqua { +"Stderr:" }
    })
    println(buildStyledString { red { +this@throwError.stderr.toString() } })
    exitProcess(1)
}