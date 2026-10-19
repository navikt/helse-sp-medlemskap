package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.Session
import kotliquery.queryOf
import no.nav.helse.sykepenger.medlemskap.application.MigreringsloggRepository
import org.intellij.lang.annotations.Language

internal class PostgresMigreringsloggRepository(
    private val session: Session,
) : MigreringsloggRepository {
    override fun finnesFor(fødselsnummer: String): Boolean {
        @Language("PostgreSQL")
        val sql = """
            select exists (
                select 1
                from migreringslogg
                where fødselsnummer = :fodselsnummer
            ) as finnes
        """
        return session.run(
            queryOf(sql, mapOf("fodselsnummer" to fødselsnummer))
                .map { it.boolean("finnes") }
                .asSingle,
        ) ?: false
    }

    override fun lagre(
        fødselsnummer: String,
        antallVurderinger: Int,
        antallHoppetOver: Int,
    ) {
        @Language("PostgreSQL")
        val sql = """
            insert into migreringslogg (
                fødselsnummer, antall_vurderinger, antall_hoppet_over
            )
            values (
                :fodselsnummer, :antallVurderinger, :antallHoppetOver
            )
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "fodselsnummer" to fødselsnummer,
                    "antallVurderinger" to antallVurderinger,
                    "antallHoppetOver" to antallHoppetOver,
                ),
            ).asUpdate,
        )
    }
}
