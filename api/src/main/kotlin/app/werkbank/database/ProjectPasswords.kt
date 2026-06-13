package app.werkbank.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class ProjectPassword(id: EntityID<Uuid>): UuidEntity(id) {
    companion object: UuidEntityClass<ProjectPassword>(ProjectPasswords)

    var project by Project referencedOn ProjectPasswords.projectId
    var password by AccessPassword referencedOn ProjectPasswords.passwordId
    var createdAt by ProjectPasswords.createdAt
    var createdBy by User referencedOn ProjectPasswords.createdBy
}

object ProjectPasswords : UuidTable("project_passwords") {
    val projectId = reference("project_id", Projects, onDelete = ReferenceOption.CASCADE)
    val passwordId = reference("password_id", AccessPasswords, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val createdBy = reference("created_by", Users, onDelete = ReferenceOption.CASCADE)

    init {
        uniqueIndex(projectId, passwordId)
    }
}