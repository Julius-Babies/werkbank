package app.werkbank.database

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class User(id: EntityID<Id>): UuidEntity(id) {
    companion object: UuidEntityClass<User>(Users)
    typealias Id = Uuid

    var username by Users.username
    var githubToken by Users.githubToken
    var createdAt by Users.createdAt
}

object Users : UuidTable("users") {
    val username = varchar("username", 255)
    val githubToken = varchar("github_token", 255)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}