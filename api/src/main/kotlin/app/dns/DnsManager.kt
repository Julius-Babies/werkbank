package app.werkbank.app.dns

interface DnsManager {

    /**
     * Creates a new A record for the given domain.
     */
    suspend fun createRecord(domain: String)
    suspend fun deleteRecord(domain: String)
}