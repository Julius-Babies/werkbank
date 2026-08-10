package commands.project

import app.config.MainConfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import es.jvbabi.tui.table.buildTable
import es.jvbabi.tui.table.components.BorderStyle
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class ProjectCommand : SuspendingCliktCommand("project"), KoinComponent {

    override val invokeWithoutSubcommand: Boolean = true

    private val mainConfig by inject<MainConfig>()

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        buildTable {
            border = BorderStyle.Borderless

            row {
                cell { +buildStyledString { bold { +"Project ID" } } }
                cell { +buildStyledString { bold { +"Name" } } }
                cell { +buildStyledString { bold { +"Location" } } }
            }

            mainConfig.getConfig().projects.orEmpty().forEach { project ->
                row {
                    cell { +project.id }
                    cell { +project.name }
                    cell { +project.path }
                }
            }
        }.let(::println)
    }
}