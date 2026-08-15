package app.data

/**
 * Thrown when the Werkbankfile declares services that the main config does not track yet,
 * e.g. because they were added after the project was imported. Only `wb setup` imports
 * services (together with hosts, certificates and the proxy configuration), so the user has
 * to run it again.
 */
class ProjectServicesNotConfiguredException(
    val projectName: String,
    val serviceNames: List<String>,
) : Exception("Services ${serviceNames.joinToString(", ")} of project $projectName are not configured")
