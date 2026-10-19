package no.nav.helse.sykepenger.medlemskap.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import kotliquery.TransactionalSession
import kotliquery.queryOf
import kotliquery.sessionOf
import no.nav.sykepenger.libs.logging.loggInfo
import org.intellij.lang.annotations.Language
import javax.sql.DataSource

internal class SlettPersonRiver(
    rapidsConnection: RapidsConnection,
    private val dataSource: DataSource,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition { it.requireValue("@event_name", "slett_person") }
                validate {
                    it.requireKey("@id", "fødselsnummer")
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = packet["fødselsnummer"].asString()
        loggInfo("Sletter person", "fødselsnummer" to fødselsnummer)

        sessionOf(dataSource).use { session ->
            session.transaction { tx -> slettPerson(tx, fødselsnummer) }
        }
        context.publish(fødselsnummer, lagPersonSlettet(fødselsnummer))
    }

    private fun slettPerson(
        tx: TransactionalSession,
        fødselsnummer: String,
    ) {
        slettMedlemskapsvurdering(tx, fødselsnummer)
        slettMedlemskapsproving(tx, fødselsnummer)
        slettMigreringslogg(tx, fødselsnummer)
    }

    private fun slettMigreringslogg(
        tx: TransactionalSession,
        fødselsnummer: String,
    ) {
        @Language("PostgreSQL")
        val query = "DELETE FROM migreringslogg WHERE fødselsnummer = :fnr"
        tx.run(queryOf(query, mapOf("fnr" to fødselsnummer)).asUpdate)
    }

    private fun slettMedlemskapsvurdering(
        tx: TransactionalSession,
        fødselsnummer: String,
    ) {
        @Language("PostgreSQL")
        val query = "DELETE FROM medlemskapsvurdering WHERE fødselsnummer = :fnr"
        tx.run(queryOf(query, mapOf("fnr" to fødselsnummer)).asUpdate)
    }

    private fun slettMedlemskapsproving(
        tx: TransactionalSession,
        fødselsnummer: String,
    ) {
        @Language("PostgreSQL")
        val query = "DELETE FROM medlemskapsproving WHERE fødselsnummer = :fnr"
        tx.run(queryOf(query, mapOf("fnr" to fødselsnummer)).asUpdate)
    }

    @Language("JSON")
    private fun lagPersonSlettet(fødselsnummer: String): String =
        """
        {
            "@event_name": "person_slettet",
            "fødselsnummer": "$fødselsnummer"
        }
        """.trimIndent()
}
