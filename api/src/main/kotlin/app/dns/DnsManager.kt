package app.werkbank.app.dns

interface DnsManager {
    suspend fun createRecord(domain: String): String
    suspend fun deleteRecord(domain: String)
}