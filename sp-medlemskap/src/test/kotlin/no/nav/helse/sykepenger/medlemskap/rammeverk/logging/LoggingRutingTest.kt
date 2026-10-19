package no.nav.helse.sykepenger.medlemskap.rammeverk.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.spi.FilterReply
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.medMdc
import no.nav.sykepenger.libs.logging.navngittLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LoggingRutingTest {
    @Test
    fun `persondetaljer og stacktrace sendes bare til Team Logs`() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val events =
            ListAppender<ILoggingEvent>().apply {
                this.context = context
                start()
            }
        root.addAppender(events)
        try {
            medMdc(MdcKey.IDENTITETSNUMMER to "test-ident") {
                navngittLogger("logging-ruting-test").error(
                    "Kall feilet",
                    IllegalStateException("persondetalj"),
                    "identitetsnummer" to "test-ident",
                )
                events.list
                    .filter { it.loggerName == "logging-ruting-test" }
                    .forEach { it.prepareForDeferredProcessing() }
            }

            val (navEvent, teamEvent) = events.list.filter { it.loggerName == "logging-ruting-test" }
            val navFilter = root.getAppender("nav-logs").copyOfAttachedFiltersList.single()
            val teamFilter = root.getAppender("team-logs").copyOfAttachedFiltersList.single()

            assertEquals(FilterReply.ACCEPT, navFilter.decide(navEvent))
            assertEquals(FilterReply.DENY, teamFilter.decide(navEvent))
            assertEquals(FilterReply.DENY, navFilter.decide(teamEvent))
            assertEquals(FilterReply.ACCEPT, teamFilter.decide(teamEvent))
            assertEquals("Kall feilet", navEvent.formattedMessage)
            assertNull(navEvent.throwableProxy)
            assertTrue(teamEvent.formattedMessage.contains("identitetsnummer: \"test-ident\""))
            assertEquals("persondetalj", teamEvent.throwableProxy.message)
            assertEquals("test-ident", teamEvent.mdcPropertyMap[MdcKey.IDENTITETSNUMMER.value])
        } finally {
            root.detachAppender(events)
        }
    }
}
