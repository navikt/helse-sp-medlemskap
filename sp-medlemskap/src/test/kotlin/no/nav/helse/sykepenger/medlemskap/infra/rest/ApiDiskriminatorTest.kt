package no.nav.helse.sykepenger.medlemskap.infra.rest

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

class ApiDiskriminatorTest {
    @Test
    fun `ApiVurderingskilde - navn i JsonSubTypes stemmer med faktisk kildetype`() {
        verifiserDiskriminator(
            ApiVurderingskilde::class.java,
            ApiVurderingskilde.Automatisk(
                versjonAvKildekode = "1",
                oppslagsperiode = ApiPeriode(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
            ),
            ApiVurderingskilde.Saksbehandler(
                ident = "Z999999",
                fritekstbegrunnelse = "en forklaring",
            ),
            ApiVurderingskilde.OverførtFraSpleis(),
            ApiVurderingskilde.OverførtFraInfotrygd(),
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
