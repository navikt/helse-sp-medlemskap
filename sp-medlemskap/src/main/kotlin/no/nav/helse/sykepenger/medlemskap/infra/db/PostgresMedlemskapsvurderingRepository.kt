package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapsvurderingRepository
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsstatus
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import org.intellij.lang.annotations.Language
import org.postgresql.util.PSQLException
import java.time.LocalDate

internal class PostgresMedlemskapsvurderingRepository(
    private val session: Session,
) : MedlemskapsvurderingRepository {
    override fun lagre(vurdering: Medlemskapsvurdering) {
        @Language("PostgreSQL")
        val sql = """
            insert into medlemskapsvurdering (
                id, fødselsnummer, skjæringstidspunkt, status, vurdert_tidspunkt, vurderingskilde, kilde
            )
            values (
                :id, :fodselsnummer, :skjaeringstidspunkt, :status, :vurdertTidspunkt, :vurderingskilde, cast(:kilde as jsonb)
            )
        """
        try {
            session.run(
                queryOf(
                    sql,
                    mapOf(
                        "id" to vurdering.id.value,
                        "fodselsnummer" to vurdering.fødselsnummer,
                        "skjaeringstidspunkt" to vurdering.skjæringstidspunkt,
                        "status" to vurdering.status.tilDbVerdi(),
                        "vurdertTidspunkt" to vurdering.vurdertTidspunkt,
                        "vurderingskilde" to vurdering.kilde.tilDbVerdi(),
                        "kilde" to Vurderingskildejson.tilJson(vurdering.kilde),
                    ),
                ).asUpdate,
            )
        } catch (e: PSQLException) {
            if (e.sqlState != UNIKHETSBRUDD) throw e
            throw IllegalStateException("Medlemskapsvurdering ${vurdering.id} er allerede lagret. Vurderinger er immutable.", e)
        }
    }

    override fun gjeldende(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Medlemskapsvurdering? = historikk(fødselsnummer, skjæringstidspunkt, grense = 1).firstOrNull()

    override fun historikk(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): List<Medlemskapsvurdering> = historikk(fødselsnummer, skjæringstidspunkt, grense = null)

    private fun historikk(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
        grense: Int?,
    ): List<Medlemskapsvurdering> {
        @Language("PostgreSQL")
        val sql = """
            $SELECT_MEDLEMSKAPSVURDERING
            where fødselsnummer = :fodselsnummer and skjæringstidspunkt = :skjaeringstidspunkt
            order by vurdert_tidspunkt desc, opprettet desc
            ${grense?.let { "limit $it" } ?: ""}
        """
        return session.run(
            queryOf(
                sql,
                mapOf(
                    "fodselsnummer" to fødselsnummer,
                    "skjaeringstidspunkt" to skjæringstidspunkt,
                ),
            ).map(::tilMedlemskapsvurdering).asList,
        )
    }

    override fun finn(medlemskapsvurderingId: MedlemskapsvurderingId): Medlemskapsvurdering? {
        @Language("PostgreSQL")
        val sql = """
            $SELECT_MEDLEMSKAPSVURDERING
            where id = :id
        """
        return session.run(queryOf(sql, mapOf("id" to medlemskapsvurderingId.value)).map(::tilMedlemskapsvurdering).asSingle)
    }

    private fun tilMedlemskapsvurdering(row: Row) =
        Medlemskapsvurdering(
            id = MedlemskapsvurderingId(row.uuid("id")),
            fødselsnummer = row.string("fødselsnummer"),
            skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
            status = row.string("status").tilMedlemskapsstatus(),
            vurdertTidspunkt = row.instant("vurdert_tidspunkt"),
            kilde = Vurderingskildejson.fraJson(row.string("kilde")),
        )

    private companion object {
        const val UNIKHETSBRUDD = "23505"

        @Language("PostgreSQL")
        const val SELECT_MEDLEMSKAPSVURDERING = """
            select id, fødselsnummer, skjæringstidspunkt, status, vurdert_tidspunkt, kilde
            from medlemskapsvurdering
        """
    }
}

private fun Medlemskapsstatus.tilDbVerdi(): String =
    when (this) {
        Medlemskapsstatus.Ja -> "JA"
        Medlemskapsstatus.Nei -> "NEI"
        Medlemskapsstatus.Uavklart -> "UAVKLART"
        Medlemskapsstatus.UavklartMedBrukerspørsmål -> "UAVKLART_MED_BRUKERSPORSMAAL"
    }

private fun String.tilMedlemskapsstatus(): Medlemskapsstatus =
    when (this) {
        "JA" -> Medlemskapsstatus.Ja
        "NEI" -> Medlemskapsstatus.Nei
        "UAVKLART" -> Medlemskapsstatus.Uavklart
        "UAVKLART_MED_BRUKERSPORSMAAL" -> Medlemskapsstatus.UavklartMedBrukerspørsmål
        else -> error("Ukjent medlemskapsstatus i databasen: $this")
    }

private fun Vurderingskilde.tilDbVerdi(): String =
    when (this) {
        is Vurderingskilde.Automatisk -> "AUTOMATISK"
        is Vurderingskilde.Saksbehandler -> "SAKSBEHANDLER"
        Vurderingskilde.OverførtFraSpleis -> "OVERFOERT_FRA_SPLEIS"
        Vurderingskilde.OverførtFraInfotrygd -> "OVERFOERT_FRA_INFOTRYGD"
    }
