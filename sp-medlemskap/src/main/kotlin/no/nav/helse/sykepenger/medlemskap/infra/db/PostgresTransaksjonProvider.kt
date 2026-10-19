package no.nav.helse.sykepenger.medlemskap.infra.db

import kotliquery.Session
import kotliquery.sessionOf
import no.nav.helse.sykepenger.medlemskap.application.Transaksjonskontekst
import no.nav.helse.sykepenger.medlemskap.rammeverk.rest.TransaksjonProvider
import javax.sql.DataSource

internal class PostgresTransaksjonProvider(
    private val dataSource: DataSource,
) : TransaksjonProvider<Transaksjonskontekst> {
    override fun <T> transaksjon(block: (Transaksjonskontekst) -> T): T =
        sessionOf(dataSource).use { session ->
            session.transaction { transaksjon -> block(PostgresTransaksjonskontekst(transaksjon)) }
        }
}

private class PostgresTransaksjonskontekst(
    session: Session,
) : Transaksjonskontekst {
    override val medlemskapsprøvinger = PostgresMedlemskapsprøvingRepository(session)
    override val medlemskapsvurderinger = PostgresMedlemskapsvurderingRepository(session)
    override val migreringslogg = PostgresMigreringsloggRepository(session)
    override val outbox = PostgresOutbox(session)
}
