package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.*

class ApiDiskriminatorTest {
    @Test
    fun `ApiMedlemskapsvurdering - navn i JsonSubTypes stemmer med faktisk kravkilde`() {
        verifiserDiskriminator(
            ApiMedlemskapsvurdering::class.java,
            ApiMedlemskapsvurdering.VurdertISpVilkarproving(
                id = UUID.randomUUID(),
                kravkode = ApiKravkode.MEDLEMSKAP,
                medlemskapOk = true,
                avgjørendeVilkårskode = ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                vurderinger =
                    listOf(
                        ApiVilkårsvurdering(
                            id = UUID.randomUUID(),
                            vilkårskode = ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                            utfall = ApiUtfall.OPPFYLT,
                            vurdertTidspunkt = Instant.now(),
                            lovreferanse = ApiLovreferanse("folketrygdloven", "8-2", 1, 1, null, LocalDate.of(2025, 12, 22)),
                            kilde =
                                ApiVurderingskilde.Automatisk(
                                    versjonAvKildekode = "1",
                                    grunnlag = ApiVurderingsgrunnlag.SelvstendigNæringsdrivende(),
                                ),
                        ),
                    ),
            ),
            ApiMedlemskapsvurdering.OverførtFraSpleis(
                id = UUID.randomUUID(),
                kravkode = ApiKravkode.MEDLEMSKAP,
                medlemskapOk = true,
                avgjørendeVilkårskode = ApiVilkårskode.MEDLEMSKAP_ARBEID_MINST_4_UKER,
                vurderinger = emptyList(),
            ),
            ApiMedlemskapsvurdering.OverførtFraInfotrygd(
                id = UUID.randomUUID(),
                kravkode = ApiKravkode.MEDLEMSKAP,
                medlemskapOk = true,
            ),
        )
    }

    @Test
    fun `ApiVurderingskilde - navn i JsonSubTypes stemmer med faktisk kildetype`() {
        verifiserDiskriminator(
            ApiVurderingskilde::class.java,
            ApiVurderingskilde.Automatisk(
                versjonAvKildekode = "1",
                grunnlag = ApiVurderingsgrunnlag.SelvstendigNæringsdrivende(),
            ),
            ApiVurderingskilde.Saksbehandler(
                ident = "Z999999",
                fritekstbegrunnelse = "en forklaring",
            ),
            ApiVurderingskilde.OverførtFraSpleis(
                grunnlag = ApiVurderingsgrunnlag.SelvstendigNæringsdrivende(),
            ),
        )
    }

    @Test
    fun `ApiVurderingsgrunnlag - navn i JsonSubTypes stemmer med faktisk grunnlagstype`() {
        verifiserDiskriminator(
            ApiVurderingsgrunnlag::class.java,
            ApiVurderingsgrunnlag.Arbeidsforhold(
                arbeidsforhold = emptyList(),
                medlemskapsperiode = null,
                medlemskapsdager = 0,
            ),
            ApiVurderingsgrunnlag.SelvstendigNæringsdrivende(),
        )
    }

    @Test
    fun `ApiVisningsfelt - navn i JsonSubTypes stemmer med faktisk type`() {
        verifiserDiskriminator(
            ApiVisningsfelt::class.java,
            ApiVisningsfelt.Tekst(tittel = "Vilkår", tekst = "Oppfylt"),
            ApiVisningsfelt.Liste(tittel = "Journalposter", elementer = listOf("1", "2")),
        )
    }

    @Test
    fun `ApiSaksbehandlerhandlingVisningResponse serialiseres med typefelt`() {
        val response =
            ApiSaksbehandlerhandlingVisningResponse(
                felter =
                    listOf(
                        ApiVisningsfelt.Tekst(tittel = "Vilkår", tekst = "Oppfylt"),
                        ApiVisningsfelt.Liste(tittel = "Journalposter", elementer = listOf("1", "2")),
                    ),
            )
        val jsonMapper =
            jacksonObjectMapper()
                .rebuild<JsonMapper, JsonMapper.Builder>()
                .accessorNaming(DefaultAccessorNamingStrategy.Provider().withFirstCharAcceptance(true, true))
                .build()

        assertEquals(
            """{"felter":[{"type":"TEKST","tittel":"Vilkår","tekst":"Oppfylt"},{"type":"LISTE","tittel":"Journalposter","elementer":["1","2"]}]}""",
            jsonMapper.writeValueAsString(response),
        )
    }

    private fun verifiserDiskriminator(
        unionstype: Class<*>,
        vararg instanser: Any,
    ) {
        val typeInfo = unionstype.getAnnotation(JsonTypeInfo::class.java) ?: error("Mangler @JsonTypeInfo på $unionstype")
        val subTypes = unionstype.getAnnotation(JsonSubTypes::class.java) ?: error("Mangler @JsonSubTypes på $unionstype")
        val forventetNavnPerKlasse = subTypes.value.associate { it.value.java to it.name }
        val gettermetode = "get" + typeInfo.property.replaceFirstChar { it.uppercase() }

        instanser.forEach { instans ->
            val forventetNavn =
                forventetNavnPerKlasse[instans::class.java]
                    ?: error("Ingen @JsonSubTypes.Type registrert for ${instans::class.java} i $unionstype")
            val faktiskVerdi =
                instans::class.java
                    .getMethod(gettermetode)
                    .invoke(instans)
                    .toString()

            assertEquals(forventetNavn, faktiskVerdi) {
                "@JsonSubTypes sier ${instans::class.simpleName} skal skrive ${typeInfo.property}=$forventetNavn, " +
                    "men instansen har $faktiskVerdi"
            }
        }
    }
}
