package app.data.extensions.project

import app.config.DEFAULT_BASE_DOMAIN
import app.data.Project

fun Project.getAllDomains(): List<String> {
    val mainDomain = "${this.id.lowercase()}.$DEFAULT_BASE_DOMAIN"
    return (this
        .getConfig()
        .http
        .flatMap { httpEntry ->
            httpEntry
                .domains
                .orEmpty()
                .filterNot { it.isBlank() }
                .map { domain -> if (domain.endsWith(".$mainDomain")) domain else "$domain.$mainDomain" }
        } + mainDomain)
        .distinct()
}