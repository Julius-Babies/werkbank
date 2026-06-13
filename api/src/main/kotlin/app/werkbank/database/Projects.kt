package app.werkbank.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class Project(id: EntityID<Uuid>): UuidEntity(id) {
    companion object: UuidEntityClass<Project>(Projects)

    var projectKey by Projects.projectKey
    var name by Projects.name
    var owner by User referencedOn Projects.owner
    var icon by Projects.icon
    var accessState by Projects.accessState
    var createdAt by Projects.createdAt

    val passwords by ProjectPassword referrersOn ProjectPasswords.projectId

    enum class AccessState {
        Disabled, Restricted, Open
    }
}

object Projects : UuidTable("projects") {
    val projectKey = varchar("project_key", 255)
    val name = varchar("name", 255)
    val owner = reference("owner", Users, onDelete = ReferenceOption.CASCADE)
    val icon = blob("icon")
    val accessState = enumerationByName<Project.AccessState>("access_state", 16)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        index(false, owner)
        uniqueIndex(projectKey, owner)
    }
}