package app.dependencies.cli

import com.kgit2.kommand.io.Output
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import util.throwError

fun runCommand(command: String, vararg args: String): Output {
    val args = args.toList()
    val result = Command(command)
        .args(args)
        .stderr(Stdio.Pipe)
        .stdout(Stdio.Pipe)
        .spawn()
        .waitWithOutput()
    if (result.status != 0) result.throwError("Failed to run command", command, args)
    return result
}