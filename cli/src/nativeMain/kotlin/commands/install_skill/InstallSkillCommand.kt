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
        $cliName postgres18 query --database <database_name> "<SQL_query>"
        
        The database name can be found using $cliName completion databases postgres18.
    """.trimIndent()
}