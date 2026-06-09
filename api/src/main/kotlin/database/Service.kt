package app.werkbank.database

import app.werkbank.config.AppConfig
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

data class ServiceHelper(val service: Service): KoinComponent {
    private val appConfig by inject<AppConfig>()

    fun getServiceDomain(): String {
        return "${service.serviceKey}-${service.project.projectKey.lowercase()}.${service.project.owner.username.lowercase()}.${appConfig.domainSuffix}"
    }
}

class Service(id: EntityID<Uuid>): UuidEntity(id) {
    companion object: UuidEntityClass<Service>(Services)
    var serviceKey by Services.serviceKey
    var project by Project referencedOn Services.project
    var createdAt by Services.createdAt
}

object Services : UuidTable("services") {
    val serviceKey = varchar("service_key", 255)
    val project = reference("project", Projects, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}