package app.data.extensions.project

import app.data.Project

fun Project.usesTraefik(): Boolean {
    return this.getConfig().services.isNotEmpty()
}