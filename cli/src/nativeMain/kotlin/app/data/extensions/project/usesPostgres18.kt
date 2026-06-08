package app.data.extensions.project

import app.data.Project

fun Project.usesPostgres18(): Boolean {
    return getConfig().dependencies?.postgres?.postgres18?.databases.orEmpty().isNotEmpty()
}