package app.werkbank.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

class AccessPassword(id: EntityID<User.Id>): UuidEntity(id) {
    companion object: UuidEntityClass<AccessPassword>(AccessPasswords)

    var label by AccessPasswords.label
    var passwordHash by AccessPasswords.passwordHash
    var createdBy by User referencedOn AccessPasswords.createdBy
    var createdAt by AccessPasswords.createdAt

    val projects by ProjectPassword referrersOn ProjectPasswords.passwordId
}

object AccessPasswords : UuidTable("access_passwords") {
    val label = varchar("label", 255)
    val passwordHash = varchar("password_hash", 255)
    val createdBy = reference("created_by", Users, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}