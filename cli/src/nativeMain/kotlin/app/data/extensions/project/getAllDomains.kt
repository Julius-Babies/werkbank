package app.data.extensions.project

import app.data.Project

fun Project.getAllDomains(): List<String> {
    val mainDomain = this.id.lowercase() + ".werkbank.space"
    return (this
        .getConfig()
        .services
        .flatMap { service ->
            service
                .domains
                .filterNot { it.isBlank() }
                .map { domain -> if (domain.endsWith(".$mainDomain")) domain else "$domain.$mainDomain" }
        } + mainDomain)
        .distinct()
}