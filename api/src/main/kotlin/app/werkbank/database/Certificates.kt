package app.werkbank.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

class Certificate(id: EntityID<User.Id>): UuidEntity(id) {
    companion object: UuidEntityClass<Certificate>(Certificates)

    var user by User referencedOn Certificates.user
    var privateKey by Certificates.privateKey
    var certificate by Certificates.certificate
    var createdAt by Certificates.createdAt
}

object Certificates : UuidTable("certificates") {
    val user = reference("user", Users, onDelete = ReferenceOption.CASCADE)
    val privateKey = blob("private_key")
    val certificate = blob("certificate")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}