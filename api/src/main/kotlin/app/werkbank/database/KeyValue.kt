package app.werkbank.database

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table.Dual.text
import org.jetbrains.exposed.v1.core.Table.Dual.varchar
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

class KeyValue(key: EntityID<String>) : Entity<String>(key) {
    companion object : EntityClass<String, KeyValue>(KeyValues)

    var value by KeyValues.value
}

object KeyValues : IdTable<String>("key_values") {
    override val id = varchar("key", 255).entityId().uniqueIndex()
    val value = text("value")
}