package no.nav.helse.sykepenger.medlemskap.infra.spleis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.navikt.tbd_libs.access_token.AccessTokenProvider
import com.github.navikt.tbd_libs.access_token.TexasClient
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

internal interface ISpleisClient {
    fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering>
}

internal class SpleisClient(
    private val scope: String,
    private val baseUrl: String,
    private val tokenProvider: AccessTokenProvider,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val timeout: Duration = Duration.ofSeconds(10),
    private val maksAntallForsøk: Int = 3,
    private val ventetidMellomForsøk: Duration = Duration.ofMillis(500),
) : ISpleisClient {
    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> {
        val m2mToken = tokenProvider.machineToken(scope)

        val body = PersonRequest(fødselsnummer)

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI("$baseUrl/api/medlemskapsvurderinger"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $m2mToken")
                .header("callId", UUID.randomUUID().toString())
                .method("POST", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()

        val response =
            httpClient.sendMedRetry(
                request = request,
                bodyHandler = HttpResponse.BodyHandlers.ofString(),
                maksAntallForsøk = maksAntallForsøk,
                ventetidMellomForsøk = ventetidMellomForsøk,
            )

        if (response.statusCode() != 200) {
            throw SpleisClientException("Uventet svar fra spleis-api (HTTP ${response.statusCode()}): ${response.body()}")
        }

        return objectMapper.readValue<MedlemskapsvurderingerResponse>(response.body()).tilDomene()
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()

        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            tokenProvider: AccessTokenProvider = TexasClient.fromEnv(),
        ): SpleisClient {
            val prod = env["NAIS_CLUSTER_NAME"]?.startsWith("prod") ?: false
            val scope = if (prod) "api://prod-gcp.tbd.spleis-api/.default" else "api://dev-gcp.tbd.spleis-api/.default"
            val baseUrl = "http://spleis-api"
            return SpleisClient(scope, baseUrl, tokenProvider)
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class PersonRequest(
            val fødselsnummer: String,
        )

        /**
         * Responsen fra `POST /api/medlemskapsvurderinger` i spleis-api.
         *
         * Endepunktet finnes ikke i Spleis ennå. Kontrakten er tenkt som motstykket til
         * `POST /api/medlemskapsvurderinger`: ett innslag per medlemskapsvurdering Spleis har lagt til grunn
         * i et vilkårsgrunnlag, med en id Spleis også sender med i behovet `MedlemskapsvurderingResultat`.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class MedlemskapsvurderingerResponse(
            val medlemskapsvurderinger: List<MedlemskapsvurderingDto>,
        ) {
            fun tilDomene(): List<SpleisMedlemskapsvurdering> = medlemskapsvurderinger.map { it.tilDomene() }
        }

        /**
         * Rå JSON-representasjon av en medlemskapsvurdering fra spleis-api. [medlemskapstatus] er bare satt
         * når kilden er SPLEIS; for vilkårsgrunnlag fra Infotrygd vet ikke Spleis hvordan medlemskap ble vurdert.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class MedlemskapsvurderingDto(
            val medlemskapsvurderingId: MedlemskapsvurderingId,
            val opprettet: LocalDateTime,
            val skjæringstidspunkt: LocalDate,
            val kilde: MedlemskapsvurderingKildeDto,
            val medlemskapstatus: MedlemskapstatusDto? = null,
        ) {
            val opprettetInstant = opprettet.atZone(ZoneId.of("Europe/Oslo")).toInstant()

            fun tilDomene(): SpleisMedlemskapsvurdering =
                when (kilde) {
                    MedlemskapsvurderingKildeDto.SPLEIS ->
                        SpleisMedlemskapsvurdering.Spleis(
                            medlemskapsvurderingId = medlemskapsvurderingId,
                            skjæringstidspunkt = skjæringstidspunkt,
                            opprettet = opprettetInstant,
                            status =
                                checkNotNull(medlemskapstatus) { "medlemskapstatus mangler for SPLEIS-vurdering $medlemskapsvurderingId" }
                                    .tilDomene(),
                        )

                    MedlemskapsvurderingKildeDto.INFOTRYGD ->
                        SpleisMedlemskapsvurdering.Infotrygd(
                            medlemskapsvurderingId = medlemskapsvurderingId,
                            skjæringstidspunkt = skjæringstidspunkt,
                            opprettet = opprettetInstant,
                        )
                }
        }

        private enum class MedlemskapsvurderingKildeDto {
            INFOTRYGD,
            SPLEIS,
        }

        /** Speiler `MedlemskapsvurderingDto` i Spleis (Ja, Nei, VetIkke, UavklartMedBrukerspørsmål). */
        private enum class MedlemskapstatusDto {
            JA,
            NEI,
            VET_IKKE,
            UAVKLART_MED_BRUKERSPORSMAAL,
            ;

            fun tilDomene() =
                when (this) {
                    JA -> Medlemskapsstatus.Ja
                    NEI -> Medlemskapsstatus.Nei
                    VET_IKKE -> Medlemskapsstatus.Uavklart
                    UAVKLART_MED_BRUKERSPORSMAAL -> Medlemskapsstatus.UavklartMedBrukerspørsmål
                }
        }
    }
}

internal class SpleisClientException(
    message: String,
) : RuntimeException(message)
