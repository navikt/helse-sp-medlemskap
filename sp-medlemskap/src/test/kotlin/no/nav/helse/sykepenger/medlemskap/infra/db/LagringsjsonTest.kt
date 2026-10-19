package no.nav.helse.sykepenger.medlemskap.infra.db

import no.nav.helse.januar
import no.nav.helse.sykepenger.medlemskap.domain.Arbeidsforhold
import no.nav.helse.sykepenger.medlemskap.domain.Lovreferanse
import no.nav.helse.sykepenger.medlemskap.domain.Medlemskapsgrunnlag
import no.nav.helse.sykepenger.medlemskap.domain.MedlemskapsprøvingId
import no.nav.helse.sykepenger.medlemskap.domain.UtledetFakta
import no.nav.helse.sykepenger.medlemskap.domain.Vurderingskilde
import no.nav.helse.til
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import java.util.UUID

internal class LagringsjsonTest {
    @Test
    fun `automatisk arbeidstakervurdering lagres på avtalt format`() {
        val grunnlag = arbeidstakergrunnlag(arbeidsforhold(ansattFom = 1.januar, ansattTom = 31.januar))
        val kilde = automatisk(grunnlag, UtledetFakta.Medlemskapstid(null, 0))

        assertEquals(
            """{"type":"AUTOMATISK","prøvingId":"$PRØVING_ID",""" +
                """"grunnlag":{"type":"ARBEIDSTAKER","arbeidsforhold":[{"orgnummer":"987654321","fom":"2018-01-01","tom":"2018-01-31","type":"ORDINÆRT"}]},""" +
                """"utledet":{"type":"MEDLEMSKAPSTID","medlemskapsperiodeFom":null,"medlemskapsperiodeTom":null,"medlemskapsdager":0},""" +
                """"versjonAvKildekode":"1"}""",
            Vurderingskildejson.tilJson(kilde),
        )
        assertEquals(kilde, mapFremOgTilbake(kilde))
    }

    @Test
    fun `selvstendig næringsdrivende lagres på avtalt format`() {
        val kilde = automatisk(Medlemskapsgrunnlag.SelvstendigNæringsdrivende, UtledetFakta.Ingen)

        assertEquals(
            """{"type":"AUTOMATISK","prøvingId":"$PRØVING_ID",""" +
                """"grunnlag":{"type":"SELVSTENDIG_NÆRINGSDRIVENDE"},""" +
                """"utledet":{"type":"INGEN_UTLEDNING"},""" +
                """"versjonAvKildekode":"1"}""",
            Vurderingskildejson.tilJson(kilde),
        )
        assertEquals(kilde, mapFremOgTilbake(kilde))
    }

    @Test
    fun `løpende arbeidsforhold beholder åpen sluttdato`() {
        val grunnlag = arbeidstakergrunnlag(arbeidsforhold(ansattFom = 1.januar, ansattTom = null))
        val kilde = automatisk(grunnlag, UtledetFakta.Medlemskapstid(null, 0))

        assertEquals(
            """{"type":"AUTOMATISK","prøvingId":"$PRØVING_ID",""" +
                """"grunnlag":{"type":"ARBEIDSTAKER","arbeidsforhold":[{"orgnummer":"987654321","fom":"2018-01-01","tom":"+999999999-12-31","type":"ORDINÆRT"}]},""" +
                """"utledet":{"type":"MEDLEMSKAPSTID","medlemskapsperiodeFom":null,"medlemskapsperiodeTom":null,"medlemskapsdager":0},""" +
                """"versjonAvKildekode":"1"}""",
            Vurderingskildejson.tilJson(kilde),
        )
        assertEquals(kilde, mapFremOgTilbake(kilde))
    }

    @Test
    fun `alle arbeidsforholdtyper tåler en rundtur`() {
        Arbeidsforhold.Arbeidsforholdtype.entries.forEach { type ->
            val grunnlag =
                arbeidstakergrunnlag(arbeidsforhold(ansattFom = 1.januar, ansattTom = 31.januar, type = type))
            val kilde = automatisk(grunnlag, UtledetFakta.Ingen)
            assertEquals(kilde, mapFremOgTilbake(kilde))
        }
    }

    @Test
    fun `flere arbeidsforhold beholder rekkefølgen`() {
        val grunnlag =
            arbeidstakergrunnlag(
                arbeidsforhold(orgnummer = "111111111", ansattFom = 1.januar, ansattTom = 15.januar),
                arbeidsforhold(orgnummer = "222222222", ansattFom = 16.januar, ansattTom = 31.januar),
            )
        val kilde = automatisk(grunnlag, UtledetFakta.Ingen)

        assertEquals(kilde, mapFremOgTilbake(kilde))
    }

    @Test
    fun `medlemskapsperiode og medlemskapsdager lagres sammen med grunnlaget`() {
        val grunnlag = arbeidstakergrunnlag(arbeidsforhold(ansattFom = 4.januar, ansattTom = 31.januar))
        val kilde = automatisk(grunnlag, UtledetFakta.Medlemskapstid(4.januar til 31.januar, 28))

        val etterRundtur = mapFremOgTilbake(kilde) as Vurderingskilde.Automatisk
        val utledetFakta = etterRundtur.utledetFakta as UtledetFakta.Medlemskapstid
        assertEquals(4.januar, utledetFakta.medlemskapsperiode?.start)
        assertEquals(31.januar, utledetFakta.medlemskapsperiode?.endInclusive)
        assertEquals(28, utledetFakta.medlemskapsdager)
    }

    @Test
    fun `saksbehandlerkilde lagres på avtalt format`() {
        val kilde =
            Vurderingskilde.Saksbehandler(
                ident = "A123456",
                fritekstbegrunnelse = "vurdert etter dialog med bruker",
                journalpostId = listOf("journalpost-1"),
            )

        assertEquals(
            """{"type":"SAKSBEHANDLER","ident":"A123456","fritekstbegrunnelse":"vurdert etter dialog med bruker",""" +
                """"journalpostId":["journalpost-1"]}""",
            Vurderingskildejson.tilJson(kilde),
        )
        assertEquals(kilde, mapFremOgTilbake(kilde))
    }

    @Test
    fun `saksbehandlerkilde uten journalpostId i lagret json faller tilbake til tomt array`() {
        val kilde =
            Vurderingskildejson.fraJson(
                """{"type":"SAKSBEHANDLER","ident":"A123456","fritekstbegrunnelse":"vurdert etter dialog med bruker"}""",
            ) as Vurderingskilde.Saksbehandler

        assertEquals(emptyList<String>(), kilde.journalpostId)
    }

    @Test
    fun `overført fra spleis lagres på avtalt format`() {
        val grunnlag = Medlemskapsgrunnlag.SelvstendigNæringsdrivende
        val kilde = Vurderingskilde.OverførtFraSpleis(grunnlag = grunnlag, utledetFakta = UtledetFakta.Ingen)

        assertEquals(
            """{"type":"OVERFOERT_FRA_SPLEIS",""" +
                """"grunnlag":{"type":"SELVSTENDIG_NÆRINGSDRIVENDE"},""" +
                """"utledet":{"type":"INGEN_UTLEDNING"}}""",
            Vurderingskildejson.tilJson(kilde),
        )
        assertEquals(kilde, mapFremOgTilbake(kilde))
    }

    @Test
    fun `ukjent grunnlagstype gir feil`() {
        assertThrows<JacksonException> {
            Vurderingskildejson.fraJson(
                """{"type":"AUTOMATISK","prøvingId":"$PRØVING_ID","grunnlag":{"type":"FISKER"},""" +
                    """"utledet":{"type":"INGEN_UTLEDNING"},"versjonAvKildekode":"1"}""",
            )
        }
    }

    @Test
    fun `ukjent kildetype gir feil`() {
        assertThrows<JacksonException> {
            Vurderingskildejson.fraJson("""{"type":"MASKINELT"}""")
        }
    }

    @Test
    fun `ukjent felt i lagret json ignoreres`() {
        val json = """{"type":"SAKSBEHANDLER","ident":"A123456","fritekstbegrunnelse":"","vurdertAv":"noe vi ikke kjenner"}"""

        assertEquals(
            Vurderingskilde.Saksbehandler(ident = "A123456", fritekstbegrunnelse = "", journalpostId = emptyList()),
            Vurderingskildejson.fraJson(json),
        )
    }

    @Test
    fun `lovreferanse med alle felt satt lagres på riktig format`() {
        val lovreferanse = Lovreferanse.`§ 8-2 første avsnitt, første setning`()

        assertEquals(
            """{"lov":"folketrygdloven","paragraf":"8-2","avsnitt":1,"setning":1,"bokstav":null,"iKraftFra":"2004-01-01"}""",
            Lovreferansejson.tilJson(lovreferanse),
        )
        assertEquals(lovreferanse, mapFremOgTilbake(lovreferanse))
    }

    @Test
    fun `lovreferanse med nullable felt satt til null blir riktig mappet tilbake`() {
        val lovreferanse =
            Lovreferanse(
                lov = "folketrygdloven",
                paragraf = "8-2",
                avsnitt = null,
                setning = null,
                bokstav = null,
                iKraftFra = 1.januar(2004),
            )

        assertEquals(
            """{"lov":"folketrygdloven","paragraf":"8-2","avsnitt":null,"setning":null,"bokstav":null,"iKraftFra":"2004-01-01"}""",
            Lovreferansejson.tilJson(lovreferanse),
        )
        assertEquals(lovreferanse, mapFremOgTilbake(lovreferanse))
    }

    private fun automatisk(
        grunnlag: Medlemskapsgrunnlag,
        utledetFakta: UtledetFakta,
    ) = Vurderingskilde.Automatisk(PRØVING_ID, grunnlag, utledetFakta, versjonAvKildekode = "1")

    private fun mapFremOgTilbake(kilde: Vurderingskilde) = Vurderingskildejson.fraJson(Vurderingskildejson.tilJson(kilde))

    private fun mapFremOgTilbake(lovreferanse: Lovreferanse) = Lovreferansejson.fraJson(Lovreferansejson.tilJson(lovreferanse))

    private companion object {
        val PRØVING_ID = MedlemskapsprøvingId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    }
}
