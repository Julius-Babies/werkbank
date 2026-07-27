package app.werkbank.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DatabaseManager(
    url: String,
) {
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
    )

    private val database = Database.connect(dataSource)

    init {
        transaction {
            SchemaUtils.create(Users)
            SchemaUtils.create(Projects)
            SchemaUtils.create(Services)
            SchemaUtils.create(Certificates)
            SchemaUtils.create(AccessPasswords, ProjectPasswords)
            SchemaUtils.create(AccessKeys)
            SchemaUtils.create(KeyValues)
            SchemaUtils.create(TunnelRequests)
            SchemaUtils.create(TunnelRequestFrames)
        }
    }

    suspend fun <T> query(block: suspend Transaction.() -> T): T {
        @Suppress("DEPRECATION")
        return newSuspendedTransaction(Dispatchers.IO, database) { block() }
    }

    fun <T> queryBlocking(block: Transaction.() -> T): T {
        return transaction(database) { block() }
    }
}