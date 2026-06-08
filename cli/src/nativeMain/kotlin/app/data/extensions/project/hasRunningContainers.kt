package app.data.extensions.project

import app.data.Project
import app.dependencies.docker.DockerContainer

suspend fun Project.hasRunningContainers(): Boolean {
    return this.getContainers().any { it.container.getState() == DockerContainer.State.Running }
}