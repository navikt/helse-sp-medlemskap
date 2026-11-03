package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import no.nav.helse.sykepenger.medlemskap.application.ForhåndsvurderingRepository
import no.nav.helse.sykepenger.medlemskap.domain.Forhåndsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.Årsak
import no.nav.helse.til
import org.intellij.lang.annotations.Language
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

private val objectMapper = jacksonObjectMapper()

internal class PostgresForhåndsvurderingRepository(
    private val session: Session,
) : ForhåndsvurderingRepository {
    override fun lagre(
        forhåndsvurdering: Forhåndsvurdering,
        melding: String,
    ) {
        @Language("PostgreSQL")
        val sql = """
            insert into forhandsvurdering (søknad_id, fødselsnummer, gyldig_fom, gyldig_tom, svar, aarsaker, melding, mottatt)
            values (:soknadId, :fodselsnummer, :gyldigFom, :gyldigTom, :svar, cast(:aarsaker as jsonb), cast(:melding as jsonb), :mottatt)
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "soknadId" to forhåndsvurdering.søknadId,
                    "fodselsnummer" to forhåndsvurdering.fødselsnummer,
                    "gyldigFom" to forhåndsvurdering.gyldigForSkjæringstidspunkt?.start,
                    "gyldigTom" to forhåndsvurdering.gyldigForSkjæringstidspunkt?.endInclusive,
                    "svar" to forhåndsvurdering.status.tilDbVerdi(),
                    "aarsaker" to forhåndsvurdering.aarsaker?.let { aarsaker -> objectMapper.writeValueAsString(aarsaker.map { ÅrsakDto(it.regelId, it.forklaringPaaUavklart) }) },
                    "melding" to melding,
                    "mottatt" to forhåndsvurdering.mottatt,
                ),
            ).asUpdate,
        )
    }

    override fun finn(fødselsnummer: String): List<Forhåndsvurdering> {
        @Language("PostgreSQL")
        val sql = """
            select søknad_id, fødselsnummer, gyldig_fom, gyldig_tom, svar, aarsaker, mottatt
            from forhandsvurdering
            where fødselsnummer = :fodselsnummer
            order by løpenummer desc
        """
        return session.run(queryOf(sql, mapOf("fodselsnummer" to fødselsnummer)).map(::tilForhåndsvurdering).asList)
    }

    private fun tilForhåndsvurdering(row: Row) =
        Forhåndsvurdering(
            søknadId = row.uuid("søknad_id"),
            fødselsnummer = row.string("fødselsnummer"),
            gyldigForSkjæringstidspunkt = row.localDateOrNull("gyldig_fom")?.let { fom -> fom til row.localDate("gyldig_tom") },
            status = row.string("svar").tilMedlemskapsstatus(),
            aarsaker = row.stringOrNull("aarsaker")?.let { json -> objectMapper.readValue<List<ÅrsakDto>>(json).map { Årsak(it.regelId, it.forklaringPaaUavklart) } },
            mottatt = row.instant("mottatt"),
        )
}
