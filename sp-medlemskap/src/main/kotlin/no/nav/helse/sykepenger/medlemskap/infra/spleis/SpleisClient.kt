package no.nav.helse.sykepenger.medlemskap.infra.spleis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.navikt.tbd_libs.access_token.AccessTokenProvider
import com.github.navikt.tbd_libs.access_token.TexasClient
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering.SpleisArbeidstaker.Ansettelsesperiode
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering.SpleisArbeidstaker.Arbeidsforhold
import no.nav.helse.til
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
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class MedlemskapsvurderingerResponse(
            val medlemskapsvurderinger: List<MedlemskapsvurderingDto>,
        ) {
            fun tilDomene(): List<SpleisMedlemskapsvurdering> = medlemskapsvurderinger.map { it.tilDomene() }
        }

        /**
         * Rå JSON-representasjon av en medlemskapsvurdering fra spleis-api, slik den kommer over
         * ledningen. Dette er utelukkende et internt format for klienten: den mappes videre til
         * [SpleisMedlemskapsvurdering], som kun tillater de gyldige kombinasjonene av type og kilde.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class MedlemskapsvurderingDto(
            val medlemskapsvurderingId: MedlemskapsvurderingId,
            val opprettet: LocalDateTime,
            val type: MedlemskapsvurderingTypeDto,
            val skjæringstidspunkt: LocalDate,
            val kilde: MedlemskapsvurderingKildeDto,
            // Følgende felter er kun satt når kilden er SPLEIS og typen er ARBEIDSTAKER. I alle andre
            // gyldige kombinasjoner har vi ingen kjennskap til om, eller hvordan, medlemskapsvilkåret
            // ble vurdert.
            val oppfylt: Boolean? = null,
            val antallDager: Int? = null,
            val medlemskapsperiode: PeriodeDto? = null,
            val arbeidsforhold: List<ArbeidsforholdDto> = emptyList(),
        ) {
            val opprettetInstant = opprettet.atZone(ZoneId.of("Europe/Oslo")).toInstant()

            fun tilDomene(): SpleisMedlemskapsvurdering =
                when (kilde to type) {
                    MedlemskapsvurderingKildeDto.SPLEIS to MedlemskapsvurderingTypeDto.ARBEIDSTAKER ->
                        SpleisMedlemskapsvurdering.SpleisArbeidstaker(
                            medlemskapsvurderingId = medlemskapsvurderingId,
                            opprettet = opprettetInstant,
                            skjæringstidspunkt = skjæringstidspunkt,
                            oppfylt = requireNotNull(oppfylt) { "oppfylt mangler for SPLEIS/ARBEIDSTAKER-vurdering $medlemskapsvurderingId" },
                            antallDager = requireNotNull(antallDager) { "antallDager mangler for SPLEIS/ARBEIDSTAKER-vurdering $medlemskapsvurderingId" },
                            medlemskapsperiode = medlemskapsperiode?.tilDomene(),
                            arbeidsforhold = arbeidsforhold.map { it.tilDomene() },
                        )

                    MedlemskapsvurderingKildeDto.SPLEIS to MedlemskapsvurderingTypeDto.SELVSTENDIG ->
                        SpleisMedlemskapsvurdering.SpleisSelvstendig(
                            medlemskapsvurderingId = medlemskapsvurderingId,
                            opprettet = opprettetInstant,
                            skjæringstidspunkt = skjæringstidspunkt,
                        )

                    MedlemskapsvurderingKildeDto.INFOTRYGD to MedlemskapsvurderingTypeDto.ARBEIDSTAKER ->
                        SpleisMedlemskapsvurdering.InfotrygdArbeidstaker(
                            medlemskapsvurderingId = medlemskapsvurderingId,
                            opprettet = opprettetInstant,
                            skjæringstidspunkt = skjæringstidspunkt,
                        )

                    else -> error("Ugyldig kombinasjon av kilde ($kilde) og type ($type) for medlemskapsvurdering $medlemskapsvurderingId")
                }
        }

        private enum class MedlemskapsvurderingTypeDto {
            ARBEIDSTAKER,
            SELVSTENDIG,
        }

        private enum class MedlemskapsvurderingKildeDto {
            INFOTRYGD,
            SPLEIS,
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class PeriodeDto(
            val fom: LocalDate,
            val tom: LocalDate,
        ) {
            fun tilDomene() = fom til tom
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class ArbeidsforholdDto(
            val organisasjonsnummer: String,
            val ansettelsesperioder: List<AnsettelsesperiodeDto>,
        ) {
            fun tilDomene() =
                Arbeidsforhold(
                    organisasjonsnummer = organisasjonsnummer,
                    ansettelsesperioder = ansettelsesperioder.map { it.tilDomene() },
                )
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private data class AnsettelsesperiodeDto(
            val fom: LocalDate,
            val tom: LocalDate?,
        ) {
            fun tilDomene() = Ansettelsesperiode(fom = fom, tom = tom)
        }
    }
}

internal class SpleisClientException(
    message: String,
) : RuntimeException(message)
