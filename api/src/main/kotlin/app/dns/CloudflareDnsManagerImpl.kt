package app.werkbank.app.dns

import app.werkbank.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

class CloudflareDnsManagerImpl: DnsManager, KoinComponent {

    private val appConfig by inject<AppConfig>()

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
            })
        }
    }

    override suspend fun createRecord(domain: String) {
        val existing = getExistingDomains()
            .firstOrNull { it.name.equals(domain, ignoreCase = true) }

        if (existing == null) {
            createCloudflareRecord(domain)
            return
        }

        if (
            existing.type == "A" &&
            existing.content == appConfig.dns.targetIp
        ) {
            return
        }

        updateCloudflareRecord(existing.id, domain)
    }

    override suspend fun createTxtRecord(domain: String, content: String) {
        val existing = getExistingDomains()
            .firstOrNull { it.name.equals(domain.removeSuffix("."), ignoreCase = true) && it.type == "TXT" }

        if (existing == null) {
            createCloudflareTxtRecord(domain, content)
            return
        }

        if (existing.content == content) return

        updateCloudflareTxtRecord(existing.id, domain, content)
    }

    override suspend fun deleteTxtRecord(domain: String) {
        val existing = getExistingDomains()
            .firstOrNull { it.name.equals(domain, ignoreCase = true) && it.type == "TXT" }
            ?: return

        val response = httpClient.delete(
            "https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflare!!.zoneId}/dns_records/${existing.id}"
        ) {
            bearerAuth(appConfig.cloudflare!!.apiToken)
        }

        require(response.status.isSuccess()) {
            "Failed to delete TXT record: ${response.bodyAsText()}"
        }
    }

    override suspend fun deleteRecord(domain: String) {
        val existing = getExistingDomains()
            .firstOrNull { it.name.equals(domain, ignoreCase = true) }
            ?: return

        val response = httpClient.delete(
            "https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflare!!.zoneId}/dns_records/${existing.id}"
        ) {
            bearerAuth(appConfig.cloudflare!!.apiToken)
        }

        require(response.status.isSuccess()) {
            "Failed to delete DNS record: ${response.bodyAsText()}"
        }
    }

    private suspend fun createCloudflareRecord(domain: String) {
        val response = httpClient.post(
            "https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflare!!.zoneId}/dns_records"
        ) {
            bearerAuth(appConfig.cloudflare!!.apiToken)
            contentType(ContentType.Application.Json)

            setBody(
                CreateCloudflareDnsRecord(
                    type = "A",
                    name = domain,
                    content = appConfig.dns.targetIp,
                    ttl = 1,
                    proxied = false,
                    comment = "Created by WerkbankCloud at ${Clock.System.now()}"
                )
            )
        }

        require(response.status.isSuccess()) {
            "Failed to create DNS record: ${response.bodyAsText()}"
        }
    }

    private suspend fun updateCloudflareRecord(
        recordId: String,
        domain: String,
    ) {
        val response = httpClient.put(
            "https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflare!!.zoneId}/dns_records/$recordId"
        ) {
            bearerAuth(appConfig.cloudflare!!.apiToken)
            contentType(ContentType.Application.Json)

            setBody(
                CreateCloudflareDnsRecord(
                    type = "A",
                    name = domain,
                    content = appConfig.dns.targetIp,
                    ttl = 1,
                    proxied = false,
                    comment = "Updated by WerkbankCloud at ${Clock.System.now()}"
                )
            )
        }

        require(response.status.isSuccess()) {
            "Failed to update DNS record: ${response.bodyAsText()}"
        }
    }

    private suspend fun createCloudflareTxtRecord(domain: String, content: String) {
        val response = httpClient.post(
            "https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflare!!.zoneId}/dns_records"
        ) {
            bearerAuth(appConfig.cloudflare!!.apiToken)
            contentType(ContentType.Application.Json)

            setBody(
                CreateCloudflareDnsRecord(
                    type = "TXT",
                    name = domain,
                    content = content,
                    ttl = 120,
                    proxied = false,
                    comment = "Created by WerkbankCloud at ${Clock.System.now()}"
                )
            )
        }

        require(response.status.isSuccess()) {
            "Failed to create TXT record: ${response.bodyAsText()}"
        }
    }

    private suspend fun updateCloudflareTxtRecord(
        recordId: String,
        domain: String,
        content: String,
    ) {
        val response = httpClient.put(
            "https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflare!!.zoneId}/dns_records/$recordId"
        ) {
            bearerAuth(appConfig.cloudflare!!.apiToken)
            contentType(ContentType.Application.Json)

            setBody(
                CreateCloudflareDnsRecord(
                    type = "TXT",
                    name = domain,
                    content = content,
                    ttl = 120,
                    proxied = false,
                    comment = "Updated by WerkbankCloud at ${Clock.System.now()}"
                )
            )
        }

        require(response.status.isSuccess()) {
            "Failed to update TXT record: ${response.bodyAsText()}"
        }
    }

    private suspend fun getExistingDomains(): List<CloudflareDnsRecordResponse.Record> {
        val response = httpClient.get("https://api.cloudflare.com/client/v4/zones/${appConfig.cloudflare!!.zoneId}/dns_records") {
            bearerAuth(appConfig.cloudflare!!.apiToken)
        }
        return response.body<CloudflareDnsRecordResponse>().result
    }
}

@Serializable
private data class CreateCloudflareDnsRecord(
    @SerialName("type") val type: String,
    @SerialName("name") val name: String,
    @SerialName("content") val content: String,
    @SerialName("ttl") val ttl: Int,
    @SerialName("proxied") val proxied: Boolean,
    @SerialName("comment") val comment: String?,
)

@Serializable
private data class CloudflareDnsRecordResponse(
    @SerialName("result") val result: List<Record>,
) {
    @Serializable
    data class Record(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("content") val content: String,
        @SerialName("type") val type: String,
    )
}