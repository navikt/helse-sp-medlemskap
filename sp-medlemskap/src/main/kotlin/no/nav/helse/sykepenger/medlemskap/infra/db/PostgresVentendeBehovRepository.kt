package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import no.nav.helse.sykepenger.medlemskap.application.VentendeBehov
import no.nav.helse.sykepenger.medlemskap.application.VentendeBehovRepository
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import org.intellij.lang.annotations.Language

internal class PostgresVentendeBehovRepository(
    private val session: Session,
) : VentendeBehovRepository {
    override fun lagre(behov: VentendeBehov) {
        @Language("PostgreSQL")
        val sql = """
            insert into ventende_behov (medlemskapsproving_id, fødselsnummer, vedtaksperiode_id, opprinnelig_behov, frist)
            values (:id, :fodselsnummer, :vedtaksperiodeId, cast(:opprinneligBehov as jsonb), :frist)
            on conflict (medlemskapsproving_id) do update
            set vedtaksperiode_id = excluded.vedtaksperiode_id,
                opprinnelig_behov = excluded.opprinnelig_behov,
                frist = excluded.frist,
                endret = now()
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "id" to behov.medlemskapsprøvingId.value,
                    "fodselsnummer" to behov.fødselsnummer,
                    "vedtaksperiodeId" to behov.vedtaksperiodeId,
                    "opprinneligBehov" to behov.opprinneligBehov,
                    "frist" to behov.frist,
                ),
            ).asUpdate,
        )
    }

    override fun finn(medlemskapsprøvingId: MedlemskapsprøvingId): VentendeBehov? {
        @Language("PostgreSQL")
        val sql = """
            select medlemskapsproving_id, fødselsnummer, vedtaksperiode_id, opprinnelig_behov, frist
            from ventende_behov
            where medlemskapsproving_id = :id
            for update
        """
        return session.run(queryOf(sql, mapOf("id" to medlemskapsprøvingId.value)).map(::tilVentendeBehov).asSingle)
    }

    override fun alle(maksAntall: Int): List<MedlemskapsprøvingId> {
        @Language("PostgreSQL")
        val sql = "select medlemskapsproving_id from ventende_behov order by opprettet limit :maksAntall"
        return session.run(
            queryOf(sql, mapOf("maksAntall" to maksAntall)).map { MedlemskapsprøvingId(it.uuid("medlemskapsproving_id")) }.asList,
        )
    }

    override fun fjern(medlemskapsprøvingId: MedlemskapsprøvingId) {
        @Language("PostgreSQL")
        val sql = "delete from ventende_behov where medlemskapsproving_id = :id"
        session.run(queryOf(sql, mapOf("id" to medlemskapsprøvingId.value)).asUpdate)
    }

    private fun tilVentendeBehov(row: Row) =
        VentendeBehov(
            medlemskapsprøvingId = MedlemskapsprøvingId(row.uuid("medlemskapsproving_id")),
            fødselsnummer = row.string("fødselsnummer"),
            vedtaksperiodeId = row.string("vedtaksperiode_id"),
            opprinneligBehov = row.string("opprinnelig_behov"),
            frist = row.instant("frist"),
        )
}
