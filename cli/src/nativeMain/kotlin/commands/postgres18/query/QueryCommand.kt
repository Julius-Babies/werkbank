package commands.postgres18.query

import app.dependencies.docker.DockerContainer
import app.dependencies.postgres.Postgres18
import app.storage.cliName
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import es.jvbabi.docker.kt.docker.DockerClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.isTerminal
import util.terminalWidth
import kotlin.system.exitProcess

private const val TIMING_ENABLED_NOTICE = "Timing is on."

class QueryCommand : SuspendingCliktCommand("query"), KoinComponent {

    val postgres18 by inject<Postgres18>()
    val dockerClient by inject<DockerClient>()

    val database by option(
        "--database", "-d",
        help = "Database name to connect to (default: werkbank)",
        completionCandidates = CompletionCandidates.Custom.fromStdout("$cliName completion databases postgres18")
    ).default("werkbank")

    val format by option(
        "--format",
        help = "Output format: table (default), csv or raw (tab separated, no header)"
    ).choice("table", "csv", "raw").default("table")

    val expandedMode by option(
        "--expanded",
        help = "One record per block instead of one per line: auto (default, switches when a row does not fit), on, off"
    ).choice("auto", "on", "off").default("auto")

    val forceExpanded by option("-x", help = "Shortcut for --expanded on").flag()

    val timing by option("--timing", help = "Print the query duration reported by the server")
        .flag("--no-timing", default = true)

    val query by argument("query", help = "SQL query to execute")

    override suspend fun run() {
        if (postgres18.container.getState() != DockerContainer.State.Running) postgres18.start()
        postgres18.waitUntilReady()

        val stream = dockerClient.containers.runCommandStream(
            containerId = postgres18.container.getId()!!,
            environment = buildMap {
                put("PGPASSWORD", "werkbank")
                if (isTerminal()) put("COLUMNS", terminalWidth().toString())
            },
            command = psqlCommand()
        )

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        coroutineScope {
            launch { stream.stdout.collect { stdout.append(it) } }
            launch { stream.stderr.collect { stderr.append(it) } }
        }
        val exitCode = stream.exitCode.await()

        // Diagnostics first: notices are raised while the statement runs, and on failure they
        // explain why there is no result below them.
        val error = stderr.toString().trimEnd('\n')
        if (error.isNotBlank()) {
            println(if (isTerminal()) styleQueryError(error) else error)
        }

        val output = stdout.lineSequence()
            .filterNot { it == TIMING_ENABLED_NOTICE }
            .filterNot { exitCode != 0 && it.startsWith("Time: ") }
            .joinToString("\n")
            .trimEnd('\n')
        if (output.isNotBlank()) {
            println(if (showsStyledTable) styleQueryOutput(output) else output)
        }

        if (exitCode != 0) exitProcess(exitCode)
    }

    /** psql renders the result itself, so it also decides when a row is too wide for the terminal. */
    private fun psqlCommand(): List<String> = buildList {
        add("psql")
        add("--username=werkbank")
        add("--dbname=$database")
        add("--no-psqlrc")
        add("--set=ON_ERROR_STOP=1")
        add("--pset=pager=off")
        when (format) {
            "csv" -> add("--csv")
            "raw" -> {
                add("--tuples-only")
                add("--no-align")
                add("--field-separator=\t")
            }
            else -> {
                add("--pset=linestyle=unicode")
                add("--pset=border=2")
                add("--pset=null=$NULL_PLACEHOLDER")
                add("--pset=footer=on")
                add("--pset=expanded=$expanded")
                if (isTerminal()) {
                    // "wrapped" folds values that are too wide into the next line, and psql falls
                    // back to one block per record only if not even that fits. Both need the width,
                    // as psql cannot ask the terminal itself through docker exec.
                    add("--pset=format=wrapped")
                    add("--pset=columns=${terminalWidth()}")
                }
            }
        }
        // psql prints the duration on its own line, which would pollute piped output.
        if (format == "table" && timing && isTerminal()) add("--command=\\timing on")
        add("--command=$query")
    }

    /** Piped output has no width to adapt to, so `auto` stays tabular there. */
    private val expanded
        get() = when {
            forceExpanded -> "on"
            expandedMode == "auto" && !isTerminal() -> "off"
            else -> expandedMode
        }

    private val showsStyledTable get() = format == "table" && isTerminal()
}
