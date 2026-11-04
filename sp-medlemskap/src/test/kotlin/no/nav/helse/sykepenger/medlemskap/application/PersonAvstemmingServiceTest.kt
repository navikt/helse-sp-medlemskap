package no.nav.helse.sykepenger.medlemskap.application

import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.sykepenger.medlemskap.infra.db.Database
import no.nav.helse.sykepenger.medlemskap.infra.db.DatabaseTest
import no.nav.helse.sykepenger.medlemskap.infra.db.Lagret
import no.nav.helse.sykepenger.medlemskap.infra.spleis.ISpleisClient
import no.nav.helse.sykepenger.medlemskap.infra.spleis.SpleisMedlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.infra.spleis.tilMedlemskapsvurdering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class PersonAvstemmingServiceTest : DatabaseTest() {
    private val transaksjon = Database.transaksjonProvider
    private var spleisSvar = emptyList<SpleisMedlemskapsvurdering>()
    private var spleisFeil: RuntimeException? = null
    private var antallSpleisKall = 0
    private val service =
        PersonAvstemmingService(
            transaksjonProvider = transaksjon,
            spleisClient =
                object : ISpleisClient {
                    override fun hentMedlemskapsvurderinger(fødselsnummer: String): List<SpleisMedlemskapsvurdering> {
                        antallSpleisKall++
                        spleisFeil?.let { throw it }
                        return spleisSvar
                    }
                },
        )

    @Test
    fun `lagrer alle vurderingene fra spleis og returnerer antallet`() {
        spleisSvar = listOf(vurdering(), vurdering())

        val antallLagret = service.lagreMedlemskapsvurderinger(FØDSELSNUMMER)

        assertEquals(2, antallLagret)
        assertEquals(2, Lagret.antallVurderinger())
        assertEquals(
            setOf(FØDSELSNUMMER),
            Lagret
                .vurderinger()
                .map { it.fødselsnummer }
                .toSet(),
        )
        assertEquals(
            Lagret.Nedlasting(
                fødselsnummer = FØDSELSNUMMER,
                antallVurderinger = 2,
                antallHoppetOver = 0,
            ),
            Lagret.nedlastinger().single(),
        )
    }

    @Test
    fun `tar vare på medlemskapsstatusen fra spleis og regner infotrygd som medlem`() {
        val fraSpleis = vurdering(Medlemskapsstatus.Nei)
        val fraInfotrygd =
            SpleisMedlemskapsvurdering.Infotrygd(
                medlemskapsvurderingId = MedlemskapsvurderingId(UUID.randomUUID()),
                opprettet = Instant.now(),
                skjæringstidspunkt = LocalDate.of(2025, 1, 1),
            )
        spleisSvar = listOf(fraSpleis, fraInfotrygd)

        assertEquals(2, service.lagreMedlemskapsvurderinger(FØDSELSNUMMER))

        val spleis = transaksjon.transaksjon { tx -> tx.medlemskapsvurderinger.finn(fraSpleis.medlemskapsvurderingId) }!!
        assertEquals(Medlemskapsstatus.Nei, spleis.status)
        assertEquals(Vurderingskilde.OverførtFraSpleis, spleis.kilde)
        assertEquals(fraSpleis.opprettet, spleis.vurdertTidspunkt)
        val infotrygd = transaksjon.transaksjon { tx -> tx.medlemskapsvurderinger.finn(fraInfotrygd.medlemskapsvurderingId) }!!
        assertEquals(Medlemskapsstatus.Ja, infotrygd.status)
        assertEquals(Vurderingskilde.OverførtFraInfotrygd, infotrygd.kilde)
    }

    @Test
    fun `hopper over spleis når personen allerede er registrert i migreringsloggen`() {
        spleisSvar = listOf(vurdering())
        assertEquals(1, service.lagreMedlemskapsvurderinger(FØDSELSNUMMER))
        spleisFeil = IllegalStateException("Spleis skal ikke kalles igjen")

        val antallNyeLagret = service.lagreMedlemskapsvurderinger(FØDSELSNUMMER)

        assertEquals(0, antallNyeLagret)
        assertEquals(1, antallSpleisKall)
        assertEquals(1, Lagret.antallVurderinger())
        assertEquals(1, Lagret.nedlastinger().size)
    }

    @Test
    fun `teller vurderinger som var lagret før migreringen`() {
        val tidligereVurdering = vurdering()
        transaksjon.transaksjon { tx -> tx.medlemskapsvurderinger.lagre(tidligereVurdering.tilMedlemskapsvurdering(FØDSELSNUMMER)) }
        spleisSvar = listOf(tidligereVurdering, vurdering())

        assertEquals(1, service.lagreMedlemskapsvurderinger(FØDSELSNUMMER))
        assertEquals(
            Lagret.Nedlasting(
                fødselsnummer = FØDSELSNUMMER,
                antallVurderinger = 2,
                antallHoppetOver = 1,
            ),
            Lagret.nedlastinger().single(),
        )
    }

    @Test
    fun `feiler hvis vurderings-ID allerede tilhører en annen person`() {
        val spleisVurdering = vurdering()
        transaksjon.transaksjon { tx ->
            tx.medlemskapsvurderinger.lagre(
                Medlemskapsvurdering.overførtFraInfotrygd(
                    id = spleisVurdering.medlemskapsvurderingId,
                    fødselsnummer = ET_ANNET_FØDSELSNUMMER,
                    skjæringstidspunkt = spleisVurdering.skjæringstidspunkt,
                    vurdertTidspunkt = spleisVurdering.opprettet,
                ),
            )
        }
        spleisSvar = listOf(spleisVurdering)

        val feil =
            assertThrows<IllegalStateException> {
                service.lagreMedlemskapsvurderinger(FØDSELSNUMMER)
            }

        assertEquals(
            "Medlemskapsvurdering ${spleisVurdering.medlemskapsvurderingId} er allerede lagret for en annen person",
            feil.message,
        )
        assertEquals(
            ET_ANNET_FØDSELSNUMMER,
            Lagret
                .vurderinger()
                .single()
                .fødselsnummer,
        )
    }

    @Test
    fun `registrerer også tomt svar slik at spleis ikke kalles igjen`() {
        assertEquals(0, service.lagreMedlemskapsvurderinger(FØDSELSNUMMER))
        assertEquals(0, Lagret.antallVurderinger())
        assertEquals(
            Lagret.Nedlasting(FØDSELSNUMMER, 0, 0),
            Lagret.nedlastinger().single(),
        )
        assertEquals(0, service.lagreMedlemskapsvurderinger(FØDSELSNUMMER))
        assertEquals(1, antallSpleisKall)
        assertEquals(1, Lagret.nedlastinger().size)
    }

    @Test
    fun `videresender feil fra spleis`() {
        val forventetFeil = IllegalStateException("Spleis er utilgjengelig")
        spleisFeil = forventetFeil

        val feil =
            assertThrows<IllegalStateException> {
                service.lagreMedlemskapsvurderinger(FØDSELSNUMMER)
            }

        assertEquals(forventetFeil, feil)
        assertEquals(0, Lagret.antallVurderinger())
        assertEquals(0, Lagret.nedlastinger().size)
    }

    // Postgres lagrer mikrosekunder, mens Instant.now() kan ha nanosekunder på Linux
    private fun vurdering(status: Medlemskapsstatus = Medlemskapsstatus.Ja) =
        SpleisMedlemskapsvurdering.Spleis(
            medlemskapsvurderingId = MedlemskapsvurderingId(UUID.randomUUID()),
            opprettet = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            skjæringstidspunkt = LocalDate.of(2026, 1, 1),
            status = status,
        )

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
        const val ET_ANNET_FØDSELSNUMMER = "12029240046"
    }
}
