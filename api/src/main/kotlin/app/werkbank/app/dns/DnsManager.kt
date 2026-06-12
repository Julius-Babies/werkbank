package app.werkbank.app.dns

interface DnsManager {

    /**
     * Creates a new A record for the given domain.
     */
    suspend fun createRecord(domain: String)
    suspend fun deleteRecord(domain: String)

    /**
     * Creates a new TXT record for the given domain.
     */
    suspend fun createTxtRecord(domain: String, content: String)
    suspend fun deleteTxtRecord(domain: String)
}