package app.data.extensions.project

import app.data.Project

/**
 * Names of the Postgres 18 databases of this project, each prefixed with the project id.
 * The prefix is added only if the Werkbankfile does not already contain it.
 */
fun Project.postgres18DatabaseNames(): List<String> = getConfig()
    .dependencies
    ?.postgres
    ?.postgres18
    ?.databases
    .orEmpty()
    .map { dbname -> id + "_" + dbname.substringAfter(id + "_") }
