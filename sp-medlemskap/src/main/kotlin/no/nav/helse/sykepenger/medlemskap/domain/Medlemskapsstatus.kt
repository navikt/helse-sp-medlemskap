package no.nav.helse.sykepenger.medlemskap.domain

/**
 * Om personen er medlem i folketrygden. Verdiene følger svarene fra medlemskapstjenesten (LovMe) slik
 * sparkel-medlemskap formidler dem: JA, NEI, UAVKLART og UAVKLART_MED_BRUKERSPORSMAAL.
 */
internal enum class Medlemskapsstatus {
    Ja,
    Nei,
    Uavklart,
    UavklartMedBrukerspørsmål,
    ;

    /** Bare et avklart nei stopper utbetaling; uavklarte svar gir varsel i Spleis, men ikke avslag. */
    val erOk: Boolean get() = this != Nei
}
