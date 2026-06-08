package app.data.extensions.project

import app.data.Project

fun Project.usesRabbit(): Boolean {
    val rabbitConfig = getConfig().dependencies?.rabbitmq ?: return false
    return rabbitConfig.vhosts.isNotEmpty()
}