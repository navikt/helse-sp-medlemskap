package no.nav.helse.sykepenger.medlemskap.infra.db

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import no.nav.helse.sykepenger.medlemskap.application.HandlingType
import no.nav.helse.sykepenger.medlemskap.application.Outbox
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvolutt
import no.nav.helse.sykepenger.medlemskap.application.OutboxKonvoluttId
import no.nav.helse.sykepenger.medlemskap.application.OutboxMelding
import org.intellij.lang.annotations.Language
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val objectMapper = jacksonObjectMapper()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutboxMeldingDto.MedlemskapsvurderingEndret::class, name = "MEDLEMSKAPSVURDERING_ENDRET"),
    JsonSubTypes.Type(value = OutboxMeldingDto.Saksbehandlerhandling::class, name = "SAKSBEHANDLERHANDLING"),
)
private sealed interface OutboxMeldingDto {
    data class MedlemskapsvurderingEndret(
        val skjæringstidspunkt: LocalDate,
        val medlemskapsvurderingId: UUID,
        val manuellVurdering: Boolean,
    ) : OutboxMeldingDto

    data class Saksbehandlerhandling(
        val skjæringstidspunkt: LocalDate,
        val saksbehandlerIdent: String,
        val handlingId: UUID,
        val handlingType: HandlingType,
        val kreverTotrinnsvurdering: Boolean,
        val tidspunkt: Instant,
    ) : OutboxMeldingDto
}

private fun OutboxMelding.tilDto(): OutboxMeldingDto =
    when (this) {
        is OutboxMelding.MedlemskapsvurderingEndret ->
            OutboxMeldingDto.MedlemskapsvurderingEndret(
                skjæringstidspunkt = skjæringstidspunkt,
                medlemskapsvurderingId = medlemskapsvurderingId,
                manuellVurdering = manuellVurdering,
            )

        is OutboxMelding.Saksbehandlerhandling ->
            OutboxMeldingDto.Saksbehandlerhandling(
                skjæringstidspunkt = skjæringstidspunkt,
                saksbehandlerIdent = saksbehandlerIdent,
                handlingId = handlingId,
                handlingType = handlingType,
                kreverTotrinnsvurdering = kreverTotrinnsvurdering,
                tidspunkt = tidspunkt,
            )
    }

private fun OutboxMeldingDto.tilOutboxMelding(): OutboxMelding =
    when (this) {
        is OutboxMeldingDto.MedlemskapsvurderingEndret ->
            OutboxMelding.MedlemskapsvurderingEndret(
                skjæringstidspunkt = skjæringstidspunkt,
                medlemskapsvurderingId = medlemskapsvurderingId,
                manuellVurdering = manuellVurdering,
            )

        is OutboxMeldingDto.Saksbehandlerhandling ->
            OutboxMelding.Saksbehandlerhandling(
                skjæringstidspunkt = skjæringstidspunkt,
                saksbehandlerIdent = saksbehandlerIdent,
                handlingId = handlingId,
                handlingType = handlingType,
                kreverTotrinnsvurdering = kreverTotrinnsvurdering,
                tidspunkt = tidspunkt,
            )
    }

internal class PostgresOutbox(
    private val session: Session,
) : Outbox {
    override fun leggTil(konvolutt: OutboxKonvolutt) {
        @Language("PostgreSQL")
        val sql = """
            insert into outbox (id, melding, fodselsnummer)
            values (:id, cast(:melding as jsonb), :fodselsnummer)
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "id" to konvolutt.id.value,
                    "melding" to objectMapper.writeValueAsString(konvolutt.melding.tilDto()),
                    "fodselsnummer" to konvolutt.identitetsnummer.value,
                ),
            ).asUpdate,
        )
    }

    override fun hentUpubliserte(maksAntall: Int): List<OutboxKonvolutt> {
        @Language("PostgreSQL")
        val sql = """
            select id, melding, fodselsnummer
            from outbox
            where publisert_tidspunkt is null
            order by sekvensnummer
            limit :maksAntall
            for update
        """
        return session.run(
            queryOf(sql, mapOf("maksAntall" to maksAntall)).map(::tilUtgåendeLøsning).asList,
        )
    }

    override fun markerSomSendt(id: OutboxKonvoluttId) {
        @Language("PostgreSQL")
        val sql = """
            update outbox set publisert_tidspunkt = :now where id = :id
        """
        session.run(queryOf(sql, mapOf("id" to id.value, "now" to Instant.now())).asUpdate)
    }

    private fun tilUtgåendeLøsning(row: Row) =
        OutboxKonvolutt(
            id = OutboxKonvoluttId(row.uuid("id")),
            melding = objectMapper.readValue<OutboxMeldingDto>(row.string("melding")).tilOutboxMelding(),
            identitetsnummer = Identitetsnummer(row.string("fodselsnummer")),
        )
}
