package app.werkbank.app.projects.create

import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.createProject() {
    authenticate("jwt") {
        post {

        }
    }
}