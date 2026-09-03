package commands.install_skill

import app.storage.cliName
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import util.buildStyledString

class InstallSkillCommand : SuspendingCliktCommand("install-skill"), KoinComponent {

    override suspend fun run() {
        val postgresSkillDirectory = File.getUserHomeDirectory().resolve(".claude").resolve("skills").resolve("wb-postgres")
        if (postgresSkillDirectory.exists()) postgresSkillDirectory.delete(recursive = true)

        postgresSkillDirectory.mkdir(recursive = true)

        postgresSkillDirectory.resolve("SKILL.md").writeText(wbPostgres18Skill())

        println(buildStyledString { green { +"Installed wb skills." } })
    }
}

fun wbPostgres18Skill(): String {
    return """
        ---
        name: werkbank-postgres18
        description: "Execute Postgres queries against the database which most werkbank projects use"
        ---
        
        Use the "$cliName postgres18 query" command to execute SQL queries against the Postgres 18 database used by most werkbank projects.
        Usage:
        $cliName postgres18 query [options] --database <database_name> "<SQL_query>"
        
        The database name can be found using $cliName completion databases postgres18.
        
        ## Output formats
        
        --format table   Default. Bordered table with a row count footer. Meant to be read by humans.
        --format csv     Comma separated with a header line. Use this when you want to parse the result.
        --format raw     Tab separated, no header, no footer. Use this to grab single values.
        
        ## Record layout (--format table only)
        
        --expanded auto  Default. One row per line, switches to one block per record if a row is too wide.
        --expanded on    Always one block per record ("[ RECORD 1 ]"). Good for wide rows with few results.
        --expanded off   Always one row per line.
        -x               Shortcut for --expanded on.
        
        ## Timing
        
        --timing         Default. Prints the server side query duration.
        --no-timing      Suppresses it.
        
        ## Notes for non interactive use
        
        The output is not piped through a terminal when you run the command, so colors and the timing
        line are omitted and --expanded auto behaves like off. Prefer --format csv or --format raw,
        they are stable to parse; the table format is only laid out for display.
    """.trimIndent()
}
