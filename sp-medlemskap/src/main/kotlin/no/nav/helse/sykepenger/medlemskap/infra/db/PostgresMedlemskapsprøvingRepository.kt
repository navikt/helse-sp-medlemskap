package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import no.nav.helse.sykepenger.medlemskap.application.MedlemskapsprøvingRepository
import no.nav.helse.sykepenger.medlemskap.domain.Grunnlagsbehov
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.til
import org.intellij.lang.annotations.Language
import java.time.LocalDate
import java.util.UUID

internal class PostgresMedlemskapsprøvingRepository(
    private val session: Session,
) : MedlemskapsprøvingRepository {
    override fun lagre(prøving: Medlemskapsprøving) {
        val tilstand = prøving.tilstand.tilLagring()

        @Language("PostgreSQL")
        val sql = """
            insert into medlemskapsproving (id, fødselsnummer, skjæringstidspunkt, oppslag_fom, oppslag_tom, startet, tilstand, utestående_behov, medlemskapsvurdering_id)
            values (:id, :fodselsnummer, :skjaeringstidspunkt, :oppslagFom, :oppslagTom, :startet, :tilstand, :behov, :medlemskapsvurderingId)
            on conflict (id) do update
            set tilstand = excluded.tilstand,
                utestående_behov = excluded.utestående_behov,
                medlemskapsvurdering_id = excluded.medlemskapsvurdering_id,
                endret = now()
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "id" to prøving.id.value,
                    "fodselsnummer" to prøving.fødselsnummer,
                    "skjaeringstidspunkt" to prøving.skjæringstidspunkt,
                    "oppslagFom" to prøving.oppslagsperiode.start,
                    "oppslagTom" to prøving.oppslagsperiode.endInclusive,
                    "startet" to prøving.startet,
                    "tilstand" to tilstand.navn,
                    "behov" to tilstand.behov,
                    "medlemskapsvurderingId" to tilstand.medlemskapsvurderingId,
                ),
            ).asUpdate,
        )
    }

    override fun finnSiste(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Medlemskapsprøving? {
        @Language("PostgreSQL")
        val sql = """
            select id, fødselsnummer, skjæringstidspunkt, oppslag_fom, oppslag_tom, startet, tilstand, utestående_behov, medlemskapsvurdering_id
            from medlemskapsproving
            where fødselsnummer = :fodselsnummer and skjæringstidspunkt = :skjaeringstidspunkt
            order by løpenummer desc
            limit 1
        """
        return session.run(
            queryOf(
                sql,
                mapOf(
                    "fodselsnummer" to fødselsnummer,
                    "skjaeringstidspunkt" to skjæringstidspunkt,
                ),
            ).map(::tilPrøving).asSingle,
        )
    }

    private fun tilPrøving(row: Row) =
        Medlemskapsprøving.fraLagring(
            id = MedlemskapsprøvingId(row.uuid("id")),
            fødselsnummer = row.string("fødselsnummer"),
            skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
            oppslagsperiode = row.localDate("oppslag_fom") til row.localDate("oppslag_tom"),
            startet = row.instant("startet"),
            tilstand =
                tilstandFraLagring(
                    navn = row.string("tilstand"),
                    behov = row.stringOrNull("utestående_behov"),
                    medlemskapsvurderingId = row.uuidOrNull("medlemskapsvurdering_id"),
                ),
        )
}

private const val VENTER_PÅ_GRUNNLAG = "VENTER_PÅ_GRUNNLAG"
private const val FULLFØRT = "FULLFØRT"

private class LagretTilstand(
    val navn: String,
    val behov: String? = null,
    val medlemskapsvurderingId: UUID? = null,
)

private fun Medlemskapsprøving.Tilstand.tilLagring() =
    when (this) {
        is Medlemskapsprøving.Tilstand.VenterPåGrunnlag -> LagretTilstand(VENTER_PÅ_GRUNNLAG, behov = behov.name)
        is Medlemskapsprøving.Tilstand.Fullført -> LagretTilstand(FULLFØRT, medlemskapsvurderingId = medlemskapsvurderingId.value)
    }

private fun tilstandFraLagring(
    navn: String,
    behov: String?,
    medlemskapsvurderingId: UUID?,
): Medlemskapsprøving.Tilstand =
    when (navn) {
        VENTER_PÅ_GRUNNLAG ->
            Medlemskapsprøving.Tilstand.VenterPåGrunnlag(
                Grunnlagsbehov.valueOf(requireNotNull(behov) { "Prøving i tilstand $navn mangler utestående behov" }),
            )

        FULLFØRT ->
            Medlemskapsprøving.Tilstand.Fullført(
                MedlemskapsvurderingId(requireNotNull(medlemskapsvurderingId) { "Prøving i tilstand $navn mangler medlemskapsvurdering" }),
            )

        else -> error("Kjenner ikke igjen lagret tilstand $navn")
    }
