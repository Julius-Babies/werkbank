package app.data

import org.koin.core.component.KoinComponent

data class Project(
    val id: String,
    val name: String,
    val path: String
): KoinComponent