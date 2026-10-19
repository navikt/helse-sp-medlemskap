package no.nav.helse.sykepenger.medlemskap.rammeverk.rest

interface ApiErrorCode {
    val httpStatus: Int
    val tittel: String
}
