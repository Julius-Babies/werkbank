package app.data.extensions.project

import app.data.Project

fun Project.usesMongo(): Boolean {
    val mongoDbConfig = getConfig().dependencies?.mongodb ?: return false
    return mongoDbConfig.databases.isNotEmpty()
}