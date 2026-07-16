package app.werkbank.database

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Function

class OctetLength(val expr: Expression<*>) : Function<Long>(LongColumnType()) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("OCTET_LENGTH(", expr, ")")
    }
}

fun Expression<*>.octetLength(): OctetLength = OctetLength(this)