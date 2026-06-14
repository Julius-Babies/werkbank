package app.werkbank.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

class AccessKey(id: EntityID<User.Id>): UuidEntity(id) {
    companion object: UuidEntityClass<AccessKey>(AccessKeys)

    var key by AccessKeys.key
    var name by AccessKeys.name
    var createdAt by AccessKeys.createdAt
    var createdBy by User referencedOn AccessKeys.createdBy
}

object AccessKeys : UuidTable("access_keys") {
    val key = varchar("key", 255)
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val createdBy = reference("created_by", Users, onDelete = ReferenceOption.CASCADE)
}