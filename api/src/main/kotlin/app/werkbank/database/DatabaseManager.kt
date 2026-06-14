package app.werkbank.database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DatabaseManager(
    url: String,
) {
    private val database = Database.connect(url)

    init {
        transaction {
            SchemaUtils.create(Users)
            SchemaUtils.create(Projects)
            SchemaUtils.create(Services)
            SchemaUtils.create(Certificates)
            SchemaUtils.create(AccessPasswords, ProjectPasswords)
            SchemaUtils.create(AccessKeys)
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