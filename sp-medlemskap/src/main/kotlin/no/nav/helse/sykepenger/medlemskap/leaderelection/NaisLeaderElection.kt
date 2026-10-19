package no.nav.helse.sykepenger.medlemskap.leaderelection

import no.nav.sykepenger.libs.logging.loggError
import org.apache.hc.client5.http.fluent.Request
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpStatus
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.util.Timeout
import tools.jackson.module.kotlin.jacksonObjectMapper

internal class NaisLeaderElection(
    private val electorGetUrl: String,
    private val hostname: String,
) : LeaderElection {
    private val objectMapper = jacksonObjectMapper()

    override fun isLeader(): Boolean =
        try {
            hentLeader() == hostname
        } catch (e: Exception) {
            loggError("Klarte ikke å hente leader fra elector. Antar at denne poden ikke er leader", e)
            false
        }

    private fun hentLeader(): String {
        val (responseCode, responseBody) =
            Request
                .get(electorGetUrl)
                .connectTimeout(Timeout.ofSeconds(1))
                .responseTimeout(Timeout.ofSeconds(2))
                .setHeader("Accept", ContentType.APPLICATION_JSON.mimeType)
                .execute()
                .handleResponse { it.code to it.entity?.let(EntityUtils::toString) }

        check(responseCode == HttpStatus.SC_OK) { "Fikk HTTP $responseCode fra elector" }
        checkNotNull(responseBody) { "Fikk tom respons fra elector" }

        val leader = objectMapper.readTree(responseBody).path("name").asString()
        check(leader.isNotBlank()) { "Respons fra elector manglet navn på leader" }
        return leader
    }
}
