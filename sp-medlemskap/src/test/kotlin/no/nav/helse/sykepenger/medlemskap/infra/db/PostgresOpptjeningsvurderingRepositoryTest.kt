package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.februar
import no.nav.helse.januar
import no.nav.helse.mars
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidssituasjon
import no.nav.helse.sykepenger.medlemskap.domain.Kategori
import no.nav.helse.sykepenger.medlemskap.domain.Lovreferanse
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsprøving
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsvurderingskilde
import no.nav.helse.sykepenger.medlemskap.domain.Utfall
import no.nav.helse.sykepenger.medlemskap.domain.UtledetFakta
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårskode
import no.nav.helse.sykepenger.medlemskap.domain.Vilkårsvurdering
import no.nav.helse.sykepenger.medlemskap.domain.VilkårsvurderingId
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostgresMedlemskapsvurderingRepositoryTest : DatabaseTest() {
    @Test
    fun `arbeidstakervurdering lagres og hentes tilbake med grunnlaget sitt`() {
        val grunnlag =
            arbeidstakergrunnlag(
                arbeidsforhold(ansattFom = 1.januar, ansattTom = 31.januar),
                arbeidsforhold(orgnummer = "123456789", ansattFom = 1.februar, ansattTom = null),
            )
        val vurdering = lagreVurdering(grunnlag)

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) } as Medlemskapsvurdering.VurdertISpVilkårsprøving

        assertEquals(vurdering.id, lagret.id)
        assertEquals(FØDSELSNUMMER, lagret.fødselsnummer)
        assertEquals(1.februar, lagret.skjæringstidspunkt)
        assertEquals(Kategori.Arbeidstaker, lagret.kategori)
        assertEquals(vurdering.erOk, lagret.erOk)
        assertEquals(vurdering.avgjørendeVilkårskode, lagret.avgjørendeVilkårskode)
        assertEquals(vurdering.vilkårsvurderinger.single().id, lagret.avgjørendeVilkårsvurdering?.id)

        val vilkårsvurdering = lagret.vilkårsvurderinger.single()
        val kilde = vilkårsvurdering.kilde as Vurderingskilde.Automatisk
        assertEquals(grunnlag, kilde.grunnlag)
        assertEquals(Medlemskapsvurderingskilde.VURDERT_I_SP_medlemskap, vilkårsvurdering.vurderingskilde)
        assertEquals(Lovreferanse.`§ 8-2 første avsnitt, første setning`(), vilkårsvurdering.lovreferanse)
        assertEquals(
            vurdering.vilkårsvurderinger
                .single()
                .vurdertTidspunkt
                .truncatedTo(ChronoUnit.MILLIS),
            vilkårsvurdering.vurdertTidspunkt.truncatedTo(ChronoUnit.MILLIS),
        )
    }

    @Test
    fun `vurdering av selvstendig næringsdrivende lagres og hentes tilbake`() {
        val vurdering = lagreVurdering(Medlemskapsgrunnlag.SelvstendigNæringsdrivende)

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) } as Medlemskapsvurdering.VurdertISpVilkårsprøving
        assertEquals(Kategori.SelvstendigNæringsdrivende, lagret.kategori)
        val vilkårsvurdering = lagret.vilkårsvurderinger.single()
        val kilde = vilkårsvurdering.kilde as Vurderingskilde.Automatisk

        assertEquals(Medlemskapsgrunnlag.SelvstendigNæringsdrivende, kilde.grunnlag)
        assertEquals(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER, lagret.avgjørendeVilkårskode)
        assertEquals(vurdering.vilkårsvurderinger.single().id, lagret.avgjørendeVilkårsvurdering?.id)
        assertEquals(Lovreferanse.`§ 8-2 første avsnitt, første setning`(), vilkårsvurdering.lovreferanse)
    }

    @Test
    fun `saksbehandlervurdering lagres og hentes tilbake`() {
        val vilkårsvurdering =
            Vilkårsvurdering.avSaksbehandler(
                vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                utfall = Utfall.IkkeOppfylt,
                saksbehandlerIdent = "A123456",
                fritekstbegrunnelse = "Ikke nok medlemskap",
                journalpostId = listOf("journalpost-1", "journalpost-2"),
            )
        val vurdering =
            Medlemskapsvurdering.avSaksbehandler(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                vilkårsvurdering = vilkårsvurdering,
            )
        transaksjon { it.medlemskapsvurderinger.lagre(vurdering) }

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) } as Medlemskapsvurdering.VurdertISpVilkårsprøving
        val lagretVilkårsvurdering = lagret.vilkårsvurderinger.single()
        val kilde = lagretVilkårsvurdering.kilde

        assertInstanceOf(Vurderingskilde.Saksbehandler::class.java, kilde)
        assertEquals("A123456", (kilde as Vurderingskilde.Saksbehandler).ident)
        assertEquals(listOf("journalpost-1", "journalpost-2"), kilde.journalpostId)
        assertEquals(Medlemskapsvurderingskilde.VURDERT_I_SP_medlemskap, lagretVilkårsvurdering.vurderingskilde)
        assertEquals(Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER.lovreferanse, vilkårsvurdering.lovreferanse)
        assertEquals(vilkårsvurdering.lovreferanse, lagretVilkårsvurdering.lovreferanse)
        // Hovedregelen alene er ikke oppfylt, og det finnes ingen unntaksvilkår i denne vurderingen, så
        // avgjørendeVilkårskode()-regelen gir null (se Vilkårsvurdering.avgjørendeVilkårskode()).
        assertNull(lagret.avgjørendeVilkårskode)
        assertNull(lagret.avgjørendeVilkårsvurdering)
        assertFalse(lagret.erOk)
    }

    @Test
    fun `infotrygdvurdering lagres uten enkeltvurderinger`() {
        val vurdertTidspunkt = Instant.parse("2020-01-01T00:00:00Z")
        val vurdering =
            Medlemskapsvurdering.fraInfotrygd(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                erOk = true,
                vurdertTidspunkt = vurdertTidspunkt,
            )
        transaksjon { it.medlemskapsvurderinger.lagre(vurdering) }

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) } as Medlemskapsvurdering.OverførtFraInfotrygd

        assertTrue(lagret.erOk)
        assertEquals(Kategori.Arbeidstaker, lagret.kategori)
        assertEquals(vurdertTidspunkt, lagret.vurdertTidspunkt)
        assertEquals(vurdering.id, transaksjon { it.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.februar) }!!.id)
    }

    @Test
    fun `vurdering overført fra spleis lagres og hentes tilbake som overført fra spleis`() {
        val grunnlag = arbeidstakergrunnlag(arbeidsforhold(ansattFom = 1.januar, ansattTom = null))
        val vurdering =
            Medlemskapsvurdering.overførtFraSpleis(
                id = MedlemskapsvurderingId.ny(),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                vilkårsvurderinger =
                    listOf(
                        Vilkårsvurdering.overførtFraSpleis(
                            vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                            utfall = Utfall.Oppfylt,
                            grunnlag = grunnlag,
                            utledetFakta = UtledetFakta.Medlemskapstid(null, 0),
                            vurdertTidspunkt = Instant.parse("2020-01-01T00:00:00Z"),
                        ),
                    ),
            )
        transaksjon { it.medlemskapsvurderinger.lagre(vurdering) }

        val lagret = transaksjon { it.medlemskapsvurderinger.finn(vurdering.id) } as Medlemskapsvurdering.OverførtFraSpleis

        assertTrue(lagret.erOk)
        assertEquals(Kategori.Arbeidstaker, lagret.kategori)
        assertEquals(vurdering.vilkårsvurderinger.single().id, lagret.avgjørendeVilkårsvurdering?.id)
        assertEquals(Medlemskapsvurderingskilde.OVERFORT_FRA_SPLEIS, lagret.vilkårsvurderinger.single().vurderingskilde)
    }

    @Test
    fun `gjeldende er vurderingen med senest vurdert tidspunkt`() {
        val første = vurderingMedVurdertTidspunkt(Instant.parse("2026-01-02T00:00:00Z"))
        val andre = vurderingMedVurdertTidspunkt(Instant.parse("2026-01-01T00:00:00Z"))
        transaksjon { it.medlemskapsvurderinger.lagre(første) }
        transaksjon { it.medlemskapsvurderinger.lagre(andre) }

        val gjeldende = transaksjon { it.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.februar) }!!

        assertEquals(første.id, gjeldende.id)
        assertEquals(2, Database.antallRader("medlemskapsvurdering"))
        assertEquals(første.id, transaksjon { it.medlemskapsvurderinger.finn(første.id) }!!.id)
    }

    @Test
    fun `vurderingens tidspunkt er hentet fra seneste relaterte vilkårsvurdering`() {
        val første = vurderingMedVurderteTidspunkter(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-03T00:00:00Z"))
        val andre = vurderingMedVurdertTidspunkt(Instant.parse("2026-01-02T00:00:00Z"))
        transaksjon { it.medlemskapsvurderinger.lagre(første) }
        transaksjon { it.medlemskapsvurderinger.lagre(andre) }

        val gjeldende = transaksjon { it.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.februar) }!!

        assertEquals(første.id, gjeldende.id)
        assertEquals(
            første.vilkårsvurderinger.first().id,
            (gjeldende as Medlemskapsvurdering.VurdertISpVilkårsprøving).avgjørendeVilkårsvurdering?.id,
        )
    }

    @Test
    fun `gjeldende skiller på skjæringstidspunkt og fødselsnummer`() {
        lagreVurdering(arbeidstakergrunnlag(), skjæringstidspunkt = 1.mars)

        assertNull(transaksjon { it.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.februar) })
        assertNull(transaksjon { it.medlemskapsvurderinger.gjeldende("12029240046", 1.mars) })
        assertEquals(
            1.mars,
            transaksjon { it.medlemskapsvurderinger.gjeldende(FØDSELSNUMMER, 1.mars) }!!.skjæringstidspunkt,
        )
    }

    @Test
    fun `historikk gir alle vurderinger på skjæringstidspunktet, nyeste først`() {
        val eldst = vurderingMedVurdertTidspunkt(Instant.parse("2026-01-01T00:00:00Z"))
        val nyest = vurderingMedVurdertTidspunkt(Instant.parse("2026-01-03T00:00:00Z"))
        val infotrygd =
            Medlemskapsvurdering.fraInfotrygd(
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = 1.februar,
                erOk = true,
                vurdertTidspunkt = Instant.parse("2026-01-02T00:00:00Z"),
            )
        transaksjon { it.medlemskapsvurderinger.lagre(nyest) }
        transaksjon { it.medlemskapsvurderinger.lagre(eldst) }
        transaksjon { it.medlemskapsvurderinger.lagre(infotrygd) }
        lagreVurdering(arbeidstakergrunnlag(), skjæringstidspunkt = 1.mars)

        val historikk = transaksjon { it.medlemskapsvurderinger.historikk(FØDSELSNUMMER, 1.februar) }

        assertEquals(listOf(nyest.id, infotrygd.id, eldst.id), historikk.map { it.id })
        assertEquals(nyest.vilkårsvurderinger.map { it.id }, (historikk.first() as Medlemskapsvurdering.VurdertISpVilkårsprøving).vilkårsvurderinger.map { it.id })
        assertTrue(transaksjon { it.medlemskapsvurderinger.historikk("12029240046", 1.februar) }.isEmpty())
    }

    @Test
    fun `samme vurdering kan ikke lagres to ganger`() {
        val vurdering = lagreVurdering(arbeidstakergrunnlag())

        assertThrows<IllegalStateException> { transaksjon { it.medlemskapsvurderinger.lagre(vurdering) } }

        assertEquals(1, Database.antallRader("medlemskapsvurdering"))
    }

    @Test
    fun `finn gir null for en ukjent vurdering`() {
        assertNull(transaksjon { it.medlemskapsvurderinger.finn(MedlemskapsvurderingId.ny()) })
    }

    private fun vurderingMedVurdertTidspunkt(vurdertTidspunkt: Instant) = vurderingMedVurderteTidspunkter(vurdertTidspunkt)

    private fun vurderingMedVurderteTidspunkter(vararg vurderteTidspunkter: Instant): Medlemskapsvurdering.VurdertISpVilkårsprøving {
        val vilkårsvurderinger =
            vurderteTidspunkter.map { vurdertTidspunkt ->
                Vilkårsvurdering.fraLagring(
                    id = VilkårsvurderingId.ny(),
                    vilkårskode = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                    utfall = Utfall.Oppfylt,
                    vurdertTidspunkt = vurdertTidspunkt,
                    kilde = Vurderingskilde.Saksbehandler("A123456", "Vurdert manuelt", journalpostId = emptyList()),
                    vurderingskilde = Medlemskapsvurderingskilde.VURDERT_I_SP_medlemskap,
                    lovreferanse = Vilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER.lovreferanse,
                )
            }
        return Medlemskapsvurdering.fraLagring(
            id = MedlemskapsvurderingId.ny(),
            fødselsnummer = FØDSELSNUMMER,
            skjæringstidspunkt = 1.februar,
            vilkårsvurderinger = vilkårsvurderinger,
            avgjørendeVilkårsvurdering = vilkårsvurderinger.first(),
            erOk = true,
        )
    }

    private fun lagreVurdering(
        grunnlag: Medlemskapsgrunnlag,
        skjæringstidspunkt: LocalDate = 1.februar,
    ): Medlemskapsvurdering.VurdertISpVilkårsprøving =
        transaksjon { kontekst ->
            when (grunnlag) {
                is Medlemskapsgrunnlag.Arbeidstaker -> {
                    val påbegynt = Medlemskapsprøving.start(FØDSELSNUMMER, skjæringstidspunkt, Arbeidssituasjon.Arbeidstaker)
                    kontekst.medlemskapsprøvinger.lagre(påbegynt.prøving)
                    val vurdering = påbegynt.prøving.motta(grunnlag)
                    kontekst.medlemskapsvurderinger.lagre(vurdering)
                    kontekst.medlemskapsprøvinger.lagre(påbegynt.prøving)
                    vurdering
                }

                else -> {
                    val påbegynt = Medlemskapsprøving.start(FØDSELSNUMMER, skjæringstidspunkt, Arbeidssituasjon.SelvstendigNæringsdrivende)
                    kontekst.medlemskapsprøvinger.lagre(påbegynt.prøving)
                    checkNotNull(påbegynt.vurdering).also { kontekst.medlemskapsvurderinger.lagre(it) }
                }
            }
        }
}
