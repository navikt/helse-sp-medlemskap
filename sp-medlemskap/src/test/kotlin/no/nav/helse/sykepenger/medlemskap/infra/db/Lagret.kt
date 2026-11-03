package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.queryOf
import kotliquery.sessionOf
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvolutt
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.application.VentendeBehov
import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import org.intellij.lang.annotations.Language
import java.util.UUID

/**
 * Leser det som er lagret i testdatabasen, så testene kan sjekke resultatet. Objektene leses på nytt
 * for hvert kall, så hent dem etter steget du vil sjekke.
 */
internal object Lagret {
    fun prøvinger(): List<Medlemskapsprøving> =
        ider("select id as verdi from medlemskapsproving order by løpenummer").map { id ->
            transaksjon { it.medlemskapsprøvinger.finn(MedlemskapsprøvingId(id)) }!!
        }

    fun vurderinger(): List<Medlemskapsvurdering> =
        ider("select id as verdi from medlemskapsvurdering order by opprettet, vurdert_tidspunkt").map { id ->
            transaksjon { it.medlemskapsvurderinger.finn(MedlemskapsvurderingId(id)) }!!
        }

    fun antallVurderinger() = Database.antallRader("medlemskapsvurdering")

    fun ventendeBehov(): List<VentendeBehov> =
        ider("select medlemskapsproving_id as verdi from ventende_behov order by opprettet").map { id ->
            transaksjon { it.ventendeBehov.finn(MedlemskapsprøvingId(id)) }!!
        }

    fun forhåndsvurderinger(): List<Forhåndsvurdering> =
        strenger("select fødselsnummer as verdi from forhandsvurdering group by fødselsnummer order by min(løpenummer)").flatMap { fødselsnummer ->
            transaksjon { it.forhåndsvurderinger.finn(fødselsnummer) }.reversed()
        }

    fun upubliserteMeldinger(): List<OutboxKonvolutt> = transaksjon { it.outbox.hentUpubliserte(Int.MAX_VALUE) }

    fun nedlastinger(): List<Nedlasting> =
        sessionOf(Database.dataSource).use { session ->
            session.run(
                queryOf("select fødselsnummer, antall_vurderinger, antall_hoppet_over from migreringslogg order by løpenummer")
                    .map { Nedlasting(it.string("fødselsnummer"), it.int("antall_vurderinger"), it.int("antall_hoppet_over")) }
                    .asList,
            )
        }

    data class Nedlasting(
        val fødselsnummer: String,
        val antallVurderinger: Int,
        val antallHoppetOver: Int,
    )

    private fun <T> transaksjon(block: (Transaksjonskontekst) -> T) = Database.transaksjonProvider.transaksjon(block)

    private fun ider(
        @Language("PostgreSQL") sql: String,
    ): List<UUID> = sessionOf(Database.dataSource).use { session -> session.run(queryOf(sql).map { it.uuid("verdi") }.asList) }

    private fun strenger(
        @Language("PostgreSQL") sql: String,
    ): List<String> = sessionOf(Database.dataSource).use { session -> session.run(queryOf(sql).map { it.string("verdi") }.asList) }
}
