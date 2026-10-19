package no.nav.helse.sykepenger.medlemskap.domain

import no.nav.helse.Periode
import no.nav.helse.april
import no.nav.helse.desember
import no.nav.helse.februar
import no.nav.helse.fredag
import no.nav.helse.januar
import no.nav.helse.lørdag
import no.nav.helse.mai
import no.nav.helse.mandag
import no.nav.helse.mars
import no.nav.helse.oktober
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold.Arbeidsforholdtype.FRILANSER
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold.Arbeidsforholdtype.ORDINÆRT
import no.nav.helse.søndag
import no.nav.helse.til
import no.nav.helse.tirsdag
import no.nav.helse.torsdag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertIs

internal class MedlemskapsregelTest {
    // 4.januar til 31.januar er nøyaktig 28 dager fram til dagen før skjæringstidspunktet
    @Test
    fun `nøyaktig 28 medlemskapsdager er oppfylt`() {
        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(1.februar, arbeidsforhold(4.januar til 31.januar)))
    }

    @Test
    fun `27 medlemskapsdager er ikke oppfylt`() {
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, arbeidsforhold(5.januar til 31.januar)))
    }

    @Test
    fun `flere enn 28 medlemskapsdager er oppfylt`() {
        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(1.februar, arbeidsforhold(1.januar til 31.januar)))
    }

    @Test
    fun `uten arbeidsforhold er medlemskap ikke oppfylt`() {
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar))
    }

    // Arbeidsforholdet må være løpende dagen før skjæringstidspunktet for å telle
    @Test
    fun `arbeidsforhold avsluttet før dagen før skjæringstidspunktet teller ikke`() {
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, arbeidsforhold(1.januar til 30.januar)))
    }

    @Test
    fun `arbeidsforhold som starter på skjæringstidspunktet teller ikke`() {
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, arbeidsforhold(1.februar til 28.februar)))
    }

    // Løpende arbeidsforhold har ansettelseperiode til LocalDate.MAX,
    // og må avkortes ved skjæringstidspunktet før dagene telles
    @Test
    fun `løpende arbeidsforhold avkortes ved skjæringstidspunktet`() {
        val løpende = Arbeidsforhold(orgnummer = ORGNUMMER, ansattFom = 1.januar, ansattTom = null, type = ORDINÆRT)
        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(1.februar, løpende))
        // 1.januar til 3.januar er bare 3 dager, selv om arbeidsforholdet er løpende
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(4.januar, løpende))
    }

    @Test
    fun `sammenhengende arbeidsforhold hos to arbeidsgivere slås sammen`() {
        val førsteJobb = arbeidsforhold(1.januar til 15.januar, orgnummer = "111111111")
        val andreJobb = arbeidsforhold(16.januar til 31.januar, orgnummer = "222222222")

        // hver for seg er ingen av dem lange nok
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, førsteJobb))
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, andreJobb))
        // til sammen utgjør de 31 sammenhengende dager
        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(1.februar, førsteJobb, andreJobb))
    }

    // Sammenhengen brytes ikke av en helg: fredag regnes som rett før påfølgende mandag
    @Test
    fun `arbeidsforhold som henger sammen over helg slås sammen`() {
        val sisteDagFørJobbskifte = 4.fredag // 26.januar 2018
        val førsteDagEtterJobbskifte = 5.mandag // 29.januar 2018
        val gammelJobb = arbeidsforhold(1.januar til sisteDagFørJobbskifte, orgnummer = "111111111")
        val nyJobb = arbeidsforhold(førsteDagEtterJobbskifte til 31.januar, orgnummer = "222222222")

        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(1.februar, gammelJobb, nyJobb))
    }

    // Bare den sammenhengende perioden fram til skjæringstidspunktet teller;
    // dager fra et tidligere, avbrutt arbeidsforhold legges ikke til
    @Test
    fun `opphold i arbeidsforhold nullstiller medlemskapen`() {
        val gammelJobb = arbeidsforhold(1.januar til 31.januar, orgnummer = "111111111")
        val nyJobb = arbeidsforhold(10.februar til 28.februar, orgnummer = "222222222")

        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.mars, gammelJobb, nyJobb))
    }

    // Et arbeidsforhold som slutter fredag regnes som løpende over helgen fram til skjæringstidspunktet,
    // og helgedagene teller med i medlemskapstiden
    @Test
    fun `arbeidsforhold som slutter fredag teller fram til skjæringstidspunkt mandag`() {
        val resultat = vurder(mandag den 2.mars(2026), arbeidsforhold((fredag den 30.januar(2026)) til (fredag den 27.februar(2026))))

        assertEquals(Utfall.Oppfylt, resultat.utfall)
        assertEquals(30.januar(2026) til 1.mars(2026), resultat.medlemskapstid.medlemskapsperiode)
        assertEquals(31, resultat.medlemskapstid.medlemskapsdager)
    }

    @Test
    fun `arbeidsforhold som slutter fredag teller fram til skjæringstidspunkt søndag`() {
        val resultat = vurder(søndag den 8.mars(2026), arbeidsforhold((lørdag den 7.februar(2026)) til (fredag den 6.mars(2026))))

        assertEquals(Utfall.Oppfylt, resultat.utfall)
        assertEquals(7.februar(2026) til 7.mars(2026), resultat.medlemskapstid.medlemskapsperiode)
        assertEquals(29, resultat.medlemskapstid.medlemskapsdager)
    }

    @Test
    fun `arbeidsforhold som slutter lørdag teller fram til skjæringstidspunkt mandag`() {
        val resultat = vurder(mandag den 2.mars(2026), arbeidsforhold((lørdag den 31.januar(2026)) til (lørdag den 28.februar(2026))))

        assertEquals(Utfall.Oppfylt, resultat.utfall)
        assertEquals(31.januar(2026) til 1.mars(2026), resultat.medlemskapstid.medlemskapsperiode)
        assertEquals(30, resultat.medlemskapstid.medlemskapsdager)
    }

    // Helgedagene fram til skjæringstidspunktet teller med, og kan avgjøre utfallet
    @Test
    fun `helgedager fram til skjæringstidspunktet kan gi nok medlemskapsdager`() {
        // 2.februar til 27.februar er bare 26 dager, men 28.februar og 1.mars teller også
        val resultat = vurder(mandag den 2.mars(2026), arbeidsforhold((mandag den 2.februar(2026)) til (fredag den 27.februar(2026))))

        assertEquals(Utfall.Oppfylt, resultat.utfall)
        assertEquals(28, resultat.medlemskapstid.medlemskapsdager)
    }

    @Test
    fun `arbeidsforhold som slutter torsdag teller ikke fram til skjæringstidspunkt mandag`() {
        val resultat = vurder(mandag den 2.mars(2026), arbeidsforhold((torsdag den 1.januar(2026)) til (torsdag den 26.februar(2026))))

        assertEquals(Utfall.IkkeOppfylt, resultat.utfall)
        assertEquals(null, resultat.medlemskapstid.medlemskapsperiode)
        assertEquals(0, resultat.medlemskapstid.medlemskapsdager)
    }

    @Test
    fun `arbeidsforhold som slutter fredag teller ikke fram til skjæringstidspunkt tirsdag`() {
        val resultat = vurder(tirsdag den 3.mars(2026), arbeidsforhold((torsdag den 1.januar(2026)) til (fredag den 27.februar(2026))))

        assertEquals(Utfall.IkkeOppfylt, resultat.utfall)
    }

    @Test
    fun `frilansoppdrag teller ikke med i medlemskapen`() {
        val frilans = Arbeidsforhold(orgnummer = ORGNUMMER, ansettelseperiode = 1.januar til 31.januar, type = FRILANSER)

        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, frilans))
    }

    @Test
    fun `frilansoppdrag binder ikke sammen arbeidsforhold`() {
        val gammelJobb = arbeidsforhold(1.januar til 10.januar, orgnummer = "111111111")
        val frilans = Arbeidsforhold(orgnummer = "222222222", ansettelseperiode = 11.januar til 20.januar, type = FRILANSER)
        val nyJobb = arbeidsforhold(21.januar til 31.januar, orgnummer = "333333333")

        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, gammelJobb, frilans, nyJobb))
    }

    @Test
    fun `frilansoppdrag påvirker ikke medlemskap fra andre arbeidsforhold`() {
        val frilans = Arbeidsforhold(orgnummer = "222222222", ansettelseperiode = 1.desember(2017) til 31.januar, type = FRILANSER)
        val jobb = arbeidsforhold(4.januar til 31.januar, orgnummer = "111111111")

        val resultat = vurder(1.februar, frilans, jobb)
        assertEquals(Utfall.Oppfylt, resultat.utfall)
        assertEquals(4.januar til 31.januar, resultat.medlemskapstid.medlemskapsperiode)
    }

    // To samtidige arbeidsforhold gir ikke dobbelt medlemskap
    @Test
    fun `overlappende arbeidsforhold teller ikke dobbelt`() {
        val enJobb = arbeidsforhold(15.januar til 31.januar, orgnummer = "111111111")
        val annenJobb = arbeidsforhold(20.januar til 31.januar, orgnummer = "222222222")

        // 17 + 12 dager ville vært nok hvis dagene ble summert, men periodene overlapper
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.februar, enJobb, annenJobb))
    }

    @Test
    fun `selvstendig næringsdrivende har alltid oppfylt medlemskap`() {
        val resultat = Medlemskapsregel.vurder(1.februar, Medlemskapsgrunnlag.SelvstendigNæringsdrivende)

        val vilkårsutfall = resultat.vilkårsutfall.single()
        assertEquals(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, vilkårsutfall.vilkårskode)
        assertIs<UtledetFakta.Ingen>(vilkårsutfall.utledetFakta)
    }

    @Test
    fun `resultatet inneholder perioden som vurderes og antall medlemskapsdager`() {
        val resultat =
            Medlemskapsregel.vurder(
                1.februar,
                Medlemskapsgrunnlag.Arbeidstaker(
                    listOf(
                        arbeidsforhold(1.desember(2017) til 15.desember(2017)),
                        arbeidsforhold(4.januar til 31.januar),
                    ),
                ),
            )

        val utledetFakta = resultat.vilkårsutfall.single().utledetFakta as UtledetFakta.Medlemskapstid
        assertEquals(4.januar, utledetFakta.medlemskapsperiode?.start)
        assertEquals(31.januar, utledetFakta.medlemskapsperiode?.endInclusive)
        assertEquals(28, utledetFakta.medlemskapsdager)
    }

    @Test
    fun `resultatet har null periode og null dager når ingen periode treffer dagen før skjæringstidspunktet`() {
        val resultat =
            Medlemskapsregel.vurder(
                1.februar,
                Medlemskapsgrunnlag.Arbeidstaker(listOf(arbeidsforhold(1.januar til 30.januar))),
            )

        val utledetFakta = resultat.vilkårsutfall.single().utledetFakta as UtledetFakta.Medlemskapstid
        assertEquals(null, utledetFakta.medlemskapsperiode)
        assertEquals(0, utledetFakta.medlemskapsdager)
    }

    // ---------------------------------------------------------------------
    // Scenarioer overført fra MedlemskapTest i sykepenger-model
    // ---------------------------------------------------------------------

    // Fra `Én dags medlemskap oppfyller ikke krav til medlemskap`
    @Test
    fun `én dags medlemskap er ikke oppfylt`() {
        val løpende = Arbeidsforhold(orgnummer = ORGNUMMER, ansattFom = 1.januar, ansattTom = null, type = ORDINÆRT)
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(2.januar, løpende))
    }

    // Fra `to tilstøtende arbeidsforhold` – to perioder hos samme arbeidsgiver
    // som ligger kant i kant skal telle som sammenhengende medlemskap
    @Test
    fun `to tilstøtende arbeidsforhold hos samme arbeidsgiver slås sammen`() {
        val første = arbeidsforhold(1.januar til 10.januar)
        val andre = Arbeidsforhold(orgnummer = ORGNUMMER, ansattFom = 11.januar, ansattTom = null, type = ORDINÆRT)

        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(29.januar, første, andre))
    }

    // Fra `Medlemskap kobler sammen gap selvom rekkefølgen ikke er kronologisk`
    @Test
    fun `arbeidsforhold slås sammen selv om rekkefølgen ikke er kronologisk`() {
        val først = arbeidsforhold(1.januar til 10.januar)
        val sist = Arbeidsforhold(orgnummer = ORGNUMMER, ansattFom = 15.januar, ansattTom = null, type = ORDINÆRT)
        val imellom = arbeidsforhold(11.januar til 14.januar)

        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(29.januar, først, sist, imellom))
    }

    // Fra `slutter på lørdag, starter på mandag` – lørdag regnes som rett før påfølgende mandag
    @Test
    fun `arbeidsforhold som slutter lørdag og starter mandag slås sammen`() {
        val gammelJobb =
            arbeidsforhold(
                (søndag den 1.oktober(2017)) til (lørdag den 30.april(2022)),
                orgnummer = "111111111",
            )
        val nyJobb =
            Arbeidsforhold(
                orgnummer = "222222222",
                ansattFom = mandag den 2.mai(2022),
                ansattTom = null,
                type = ORDINÆRT,
            )

        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(2.mai(2022), gammelJobb, nyJobb))
    }

    // Fra `slutter på fredag, starter på mandag` – fredag regnes som rett før påfølgende mandag,
    // akkurat som lørdag; lang historikk hos gammel arbeidsgiver teller med
    @Test
    fun `arbeidsforhold som slutter fredag og starter mandag slås sammen`() {
        val gammelJobb =
            arbeidsforhold(
                (søndag den 1.oktober(2017)) til (fredag den 29.april(2022)),
                orgnummer = "111111111",
            )
        val nyJobb =
            Arbeidsforhold(
                orgnummer = "222222222",
                ansattFom = mandag den 2.mai(2022),
                ansattTom = null,
                type = ORDINÆRT,
            )

        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(tirsdag den 3.mai(2022), gammelJobb, nyJobb))
    }

    // Fra `slutter på torsdag, starter på mandag` – torsdag er ikke rett før mandag,
    // så oppholdet bryter medlemskapen selv om det bare er snakk om noen få dager
    @Test
    fun `arbeidsforhold som slutter torsdag og starter mandag slås ikke sammen`() {
        val gammelJobb =
            arbeidsforhold(
                (søndag den 1.oktober(2017)) til (torsdag den 28.april(2022)),
                orgnummer = "111111111",
            )
        val nyJobb =
            Arbeidsforhold(
                orgnummer = "222222222",
                ansattFom = mandag den 2.mai(2022),
                ansattTom = null,
                type = ORDINÆRT,
            )

        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(tirsdag den 3.mai(2022), gammelJobb, nyJobb))
    }

    // Fra subsumsjonstestene for § 8-2 ledd 1 – et arbeidsforhold som fortsatt løper
    // etter skjæringstidspunktet skal avkortes, slik at bare dagene før teller
    @Test
    fun `arbeidsforhold som varer forbi skjæringstidspunktet avkortes`() {
        // 4.desember til 31.desember er 28 dager
        val nøyaktigNok = arbeidsforhold(4.desember(2017) til 31.januar)
        assertEquals(Utfall.Oppfylt, vurderArbeidstaker(1.januar, nøyaktigNok))

        // 5.desember til 31.desember er 27 dager
        val énForLite = arbeidsforhold(5.desember(2017) til 31.januar)
        assertEquals(Utfall.IkkeOppfylt, vurderArbeidstaker(1.januar, énForLite))
    }

    private companion object {
        const val ORGNUMMER = "987654321"

        fun arbeidsforhold(
            ansettelseperiode: Periode,
            orgnummer: String = ORGNUMMER,
        ) = Arbeidsforhold(orgnummer = orgnummer, ansettelseperiode = ansettelseperiode, type = ORDINÆRT)

        val MedlemskapsregelResultat.utfall get() = this.vilkårsutfall.single().utfall
        val MedlemskapsregelResultat.medlemskapstid get() = this.vilkårsutfall.single().utledetFakta as UtledetFakta.Medlemskapstid

        fun vurder(
            skjæringstidspunkt: LocalDate,
            vararg arbeidsforhold: Arbeidsforhold,
        ) = Medlemskapsregel.vurder(skjæringstidspunkt, Medlemskapsgrunnlag.Arbeidstaker(arbeidsforhold.toList()))

        fun vurderArbeidstaker(
            skjæringstidspunkt: LocalDate,
            vararg arbeidsforhold: Arbeidsforhold,
        ) = vurder(skjæringstidspunkt, *arbeidsforhold).utfall
    }
}
