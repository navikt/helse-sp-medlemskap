package no.nav.helse.sykepenger.medlemskap.domain

import java.time.Instant
import java.time.LocalDate

/**
 * Vurderingen av medlemskapsvilkåret for et skjæringstidspunkt. Vurderinger er immutable: en ny
 * vurdering (automatisk eller manuell) av samme skjæringstidspunkt blir en ny rad, og den nyeste er
 * den gjeldende.
 */
internal data class Medlemskapsvurdering(
    val id: MedlemskapsvurderingId,
    val fødselsnummer: String,
    val skjæringstidspunkt: LocalDate,
    val status: Medlemskapsstatus,
    val vurdertTidspunkt: Instant,
    val kilde: Vurderingskilde,
) {
    val erOk: Boolean get() = status.erOk

    companion object {
        fun automatisk(
            prøving: Medlemskapsprøving,
            status: Medlemskapsstatus,
            versjonAvKode: String,
            aarsaker: List<Årsak>? = null,
        ) = Medlemskapsvurdering(
            id = MedlemskapsvurderingId.ny(),
            fødselsnummer = prøving.fødselsnummer,
            skjæringstidspunkt = prøving.skjæringstidspunkt,
            status = status,
            vurdertTidspunkt = Instant.now(),
            kilde = Vurderingskilde.Automatisk(prøving.id, prøving.oppslagsperiode, versjonAvKode, aarsaker),
        )

        fun avSaksbehandler(
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            status: Medlemskapsstatus,
            saksbehandlerIdent: String,
            fritekstbegrunnelse: String,
            journalpostId: List<String> = emptyList(),
        ): Medlemskapsvurdering {
            require(status == Medlemskapsstatus.Ja || status == Medlemskapsstatus.Nei) {
                "En saksbehandler må konkludere med Ja eller Nei, fikk $status"
            }
            return Medlemskapsvurdering(
                id = MedlemskapsvurderingId.ny(),
                fødselsnummer = fødselsnummer,
                skjæringstidspunkt = skjæringstidspunkt,
                status = status,
                vurdertTidspunkt = Instant.now(),
                kilde = Vurderingskilde.Saksbehandler(saksbehandlerIdent, fritekstbegrunnelse, journalpostId),
            )
        }

        fun overførtFraSpleis(
            id: MedlemskapsvurderingId,
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            status: Medlemskapsstatus,
            vurdertTidspunkt: Instant,
        ) = Medlemskapsvurdering(id, fødselsnummer, skjæringstidspunkt, status, vurdertTidspunkt, Vurderingskilde.OverførtFraSpleis)

        /** Infotrygd har ikke formidlet noe medlemskapssvar; at perioden ble utbetalt der tolkes som at personen var medlem. */
        fun overførtFraInfotrygd(
            id: MedlemskapsvurderingId,
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            vurdertTidspunkt: Instant,
        ) = Medlemskapsvurdering(id, fødselsnummer, skjæringstidspunkt, Medlemskapsstatus.Ja, vurdertTidspunkt, Vurderingskilde.OverførtFraInfotrygd)
    }
}
