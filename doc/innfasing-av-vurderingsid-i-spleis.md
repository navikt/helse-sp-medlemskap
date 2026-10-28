# Innfasing av vurderings-id i Spleis

Dette dokumentet beskriver hvordan Spleis gikk fra å vurdere opptjening selv til å bruke `opptjeningsvurderingId` og appen sp-vilkarsproving. Det inneholder også en generell oppskrift og en plan for å gjøre det samme med medlemskap i sp-medlemskap.

Hovedgrepet var å skille id fra vurdering. Alle vilkårsgrunnlag fikk først en id. Deterministiske id-er for gamle grunnlag og tilfeldige id-er for nye. Spleis begynte så å spørre den nye appen om resultatet for id-en, men i starten svarte den nye appen med Spleis sitt eget svar. Først etter det byttet vi hvem som faktisk gjør vurderingen, og til slutt migrerte vi historikken.

Historikken, den generelle oppskriften og planen for medlemskap bruker de samme stegene 1–10 med samme navn. Steg 5 betyr altså det samme i alle tre delene. Historikken følger stegene, ikke datoene: Noen steg ble gjort parallelt, og tidslinjen viser hvilket steg hver commit hører til.

Datoene under er commit-datoer. Lenkene peker til [navikt/helse-spleis](https://github.com/navikt/helse-spleis) og [navikt/helse-sp-vilkarsproving](https://github.com/navikt/helse-sp-vilkarsproving).

## Innhold

- [Tidslinje](#tidslinje)
- [Steg 1: Bygg den nye appen](#steg-1-bygg-den-nye-appen)
- [Steg 2: Id på alle vilkårsgrunnlag](#steg-2-id-på-alle-vilkårsgrunnlag)
- [Steg 3: Eksponer id-en og den gamle vurderingen](#steg-3-eksponer-id-en-og-den-gamle-vurderingen)
- [Steg 4: Kanal for endrede vurderinger](#steg-4-kanal-for-endrede-vurderinger)
- [Steg 5: Reserveløsningen i den nye appen](#steg-5-reserveløsningen-i-den-nye-appen)
- [Steg 6: Spleis spør den nye appen om resultatet](#steg-6-spleis-spør-den-nye-appen-om-resultatet)
- [Steg 7: Den nye appen gjør vurderingen](#steg-7-den-nye-appen-gjør-vurderingen)
- [Steg 8: Migrer historikken](#steg-8-migrer-historikken)
- [Steg 9: Id-en i godkjenningsbehovet](#steg-9-id-en-i-godkjenningsbehovet)
- [Steg 10: Rydd opp](#steg-10-rydd-opp)
- [Generell oppskrift](#generell-oppskrift)
- [Steg for steg for medlemskap](#steg-for-steg-for-medlemskap)

## Tidslinje

| Steg | Dato | Repo | Hva | Commit |
|---|---|---|---|---|
| 1 | 12.–18.08 | spleis | Vilkårsprøving som modul i Spleis, deretter skilt ut | [`f2fecef`](https://github.com/navikt/helse-spleis/commit/f2fecef69576ca7cf5376af61ca6af4754ec05af), [`a3fbbbd`](https://github.com/navikt/helse-spleis/commit/a3fbbbd01c6d0d8719b4535aa7087ec02a635a76) |
| 1 | 18.08 | sp-vilkarsproving | Nytt repo med kode kopiert fra Spleis | [`ca09b71`](https://github.com/navikt/helse-sp-vilkarsproving/commit/ca09b717cf7d2fefb64b2f9d2f048547f913b51f) |
| 2 | 19.08 | spleis | Migrering V345 lages, men kobles ikke inn | [`9eb7b5b`](https://github.com/navikt/helse-spleis/commit/9eb7b5b5bac131806ad265229693463677ae42af) |
| 2 | 20.08 | spleis | V345 kobles inn sammen med id på nye grunnlag | [`ba7997e`](https://github.com/navikt/helse-spleis/commit/ba7997eed67ee12f8430c929d8c50d9f14a20891) |
| 3 | 20.08 | spleis | Id-en i Spanner og GraphQL, API for opptjeningsvurderinger | [`0bbd37f`](https://github.com/navikt/helse-spleis/commit/0bbd37fe802171e0a5f68238a1eddc25984aae2a), [`471e637`](https://github.com/navikt/helse-spleis/commit/471e6376658781b9f50f75e41bb743468c156db5), [`ce26986`](https://github.com/navikt/helse-spleis/commit/ce26986e8367afa916ac47cc80d706a084c40c41) |
| 2 | 20.08 | spleis | Overstyring av arbeidsforhold gir ny id | [`41c5dbe`](https://github.com/navikt/helse-spleis/commit/41c5dbe433f0a56b128df1713ee3b994e051c455), [`a220fa8`](https://github.com/navikt/helse-spleis/commit/a220fa82ba1b768842a0b6558e03e8c0370bfce8) |
| 2 | 21.08 | spleis | V346 skriver id-ene på nytt | [`ae4f266`](https://github.com/navikt/helse-spleis/commit/ae4f266afc73f55e3a77ee81f0d3ee89dc3698ca) |
| 3 | 21.08 | spleis | API-et håndterer selvstendig | [`7234c73`](https://github.com/navikt/helse-spleis/commit/7234c73d5fe89cb7b96cd565f4d3cd2fe0d06b75) |
| 4 | 21.–24.08 | spleis | `EndretVurderingPåSkjæringstidspunkt` og river for `endret_opptjeningsvurdering` | [`d1effe9`](https://github.com/navikt/helse-spleis/commit/d1effe9b1e2bd40b93b888a9af499d43f01921fa), [`ae050bc`](https://github.com/navikt/helse-spleis/commit/ae050bc6efa53c87e8215b4bc9001abd86e714ab) |
| 5 | 24.08 | sp-vilkarsproving | Spør Spleis-API-et når id-en er ukjent | [`1646375`](https://github.com/navikt/helse-sp-vilkarsproving/commit/1646375c7c330325a617f2b49ae437b471846204) |
| 6 | 26.08 | spleis | `OpptjeningsvurderingResultat` bak toggle i dev | [`4a93e5e`](https://github.com/navikt/helse-spleis/commit/4a93e5e89fc4577383d437ac28a2bbd47dceef89) |
| 3 | 26.08 | spleis | Id-en i GraphQL også for Infotrygd-grunnlag | [`26a0148`](https://github.com/navikt/helse-spleis/commit/26a014896a49cd2acecb4afa8b90cd32c92965f5) |
| 5 | 27.08 | sp-vilkarsproving | Deploy til prod | [`b2d522d`](https://github.com/navikt/helse-sp-vilkarsproving/commit/b2d522d8420292b625289c1afbbb931ad2592665) |
| 6 | 27.08 | spleis | `OpptjeningsvurderingResultat` i prod, toggle og reserveløsning fjernet | [`4e6d1ab`](https://github.com/navikt/helse-spleis/commit/4e6d1ab6ac4ee2f78003484ac2ac84fd7ef4f9ab), [`b969787`](https://github.com/navikt/helse-spleis/commit/b96978799eb100d22aec33202e544fd0036ce3ad) |
| 7 | 27.08 | spleis | `Opptjeningsvurdering`-behov bak toggle i dev | [`d8558c8`](https://github.com/navikt/helse-spleis/commit/d8558c8dc7fdf4a16ccb5a703a975cbf652e508e) |
| 3 | 28.09 | spleis | `opprettet` på vurderingene i API-et | [`d99ba29`](https://github.com/navikt/helse-spleis/commit/d99ba29dbf96d9f50439e1f00fc99b416edd99eb) |
| 7 | 01.10 | spleis | `Opptjeningsvurdering`-behov i prod | [`7f0a89f`](https://github.com/navikt/helse-spleis/commit/7f0a89fd90049609bd4944cdc47b707f059b8f50) |
| 7 | 01.10 | sp-vilkarsproving | Sammenligner egen vurdering med Spleis sin | [`a0329a9`](https://github.com/navikt/helse-sp-vilkarsproving/commit/a0329a91d199f415cbe3bf03f7b28a972853cdc9) |
| 8 | 05.10 | sp-vilkarsproving | Migrering av historikk ved oppslag og `person_avstemming` i dev | [`dc66b08`](https://github.com/navikt/helse-sp-vilkarsproving/commit/dc66b08933a38c9702664b1d2c40448c72abae3f), [`82bd9f4`](https://github.com/navikt/helse-sp-vilkarsproving/commit/82bd9f48c9b22cfe3193d2842f155c0fdac2230a) |
| 7 | 08.10 | sp-vilkarsproving | Sammenligning fjernet etter en uke uten avvik | [`f30c7a5`](https://github.com/navikt/helse-sp-vilkarsproving/commit/f30c7a5c89fe66ce008bcc54c415fb4892d0acf8) |
| 8 | 08.–11.10 | sp-vilkarsproving | `person_avstemming` i prod, skrudd av og på igjen | [`72fa081`](https://github.com/navikt/helse-sp-vilkarsproving/commit/72fa081d5887378055ad0cbaf9503a519353ab3a), [`bbc5e78`](https://github.com/navikt/helse-sp-vilkarsproving/commit/bbc5e78bbd4d2966ebd15d2b3e1e0d304492a08c), [`755c51a`](https://github.com/navikt/helse-sp-vilkarsproving/commit/755c51a371001f137263acf7564d376d6212435e) |
| 9 | 12.10 | spleis | Id-en i `Godkjenningsbehov` | [`b487c18`](https://github.com/navikt/helse-spleis/commit/b487c184c164dd66b70a6474117f39ebaf311bc3) |

## Steg 1: Bygg den nye appen

**Hva:** sp-vilkarsproving ble laget som egen app og deployet i dev. Den svarer på behovet som lager en vurdering, og behovet som returnerer resultatet for en id.

Først prøvde vi en annen vei. Vi skrev domenekoden og riverne som en egen Gradle-modul i spleis-repoet (`sykepenger-vilkarsproving`), og kjørte den som en del av Spleis. Etter under en uke skilte vi modulen ut som eget repo og egen app ([`a3fbbbd`](https://github.com/navikt/helse-spleis/commit/a3fbbbd01c6d0d8719b4535aa7087ec02a635a76), [`ca09b71`](https://github.com/navikt/helse-sp-vilkarsproving/commit/ca09b717cf7d2fefb64b2f9d2f048547f913b51f)).

Én idé fra denne fasen ble med videre: Den nye appen lagrer ikke behovet fra Spleis. Den legger det opprinnelige behovet ved sitt eget utgående behov og svarer på det når løsningen kommer tilbake ([`3bfbd06`](https://github.com/navikt/helse-spleis/commit/3bfbd0612f)). sp-medlemskap gjør det samme med `opprinneligBehov`.

## Steg 2: Id på alle vilkårsgrunnlag

**Hva:** Alle vilkårsgrunnlag fikk feltet `opptjeningsvurderingId`. Gamle grunnlag fikk id gjennom JSON-migreringen V345. Nye grunnlag fikk en tilfeldig id når de ble opprettet. Infotrygd-grunnlag fikk også id.

Migreringen lager id-en ut fra `vilkårsgrunnlagId`:

`sykepenger-serde/src/main/kotlin/no/nav/helse/serde/migration/V345LeggeTilOpptjeningsvurderingId.kt` ([`9eb7b5b`](https://github.com/navikt/helse-spleis/commit/9eb7b5b5bac131806ad265229693463677ae42af))

```diff
+internal class V345LeggeTilOpptjeningsvurderingId : JsonMigration(version = 345) {
+    override val description = "Legger til opptjeningsburderingId på alle vilkårsgrunnlag"
+
+    override fun doMigration(jsonNode: ObjectNode, meldingerSupplier: MeldingerSupplier) {
+        jsonNode.path("vilkårsgrunnlagHistorikk").forEach { historikkInnslag ->
+            historikkInnslag.path("vilkårsgrunnlag").forEach { vilkårsgrunnlag ->
+                vilkårsgrunnlag as ObjectNode
+                val vilkårsgrunnlagId = vilkårsgrunnlag["vilkårsgrunnlagId"].asText()
+                vilkårsgrunnlag.put("opptjeningsvurderingId",
+                    UUID.nameUUIDFromBytes("$vilkårsgrunnlagId:Opptjening".toByteArray()).toString())
+            }
+        }
+    }
+}
```

Nye grunnlag får tilfeldig id, og feltet blir påkrevd i modellen:

`sykepenger-model/.../hendelser/Vilkårsgrunnlag.kt` og `sykepenger-model/.../person/VilkårsgrunnlagHistorikk.kt` ([`ba7997e`](https://github.com/navikt/helse-spleis/commit/ba7997eed67ee12f8430c929d8c50d9f14a20891))

```diff
             vilkårsgrunnlagId = UUID.randomUUID(),
             forsikringsvurderingId = forsikringsvurderingId,
+            opptjeningsvurderingId = UUID.randomUUID(),
         )
```

```diff
     internal class InfotrygdVilkårsgrunnlag(
         skjæringstidspunkt: LocalDate,
         inntektsgrunnlag: Inntektsgrunnlag,
-        vilkårsgrunnlagId: UUID = UUID.randomUUID()
-    ) : VilkårsgrunnlagElement(vilkårsgrunnlagId, skjæringstidspunkt, inntektsgrunnlag, null) {
+        vilkårsgrunnlagId: UUID ,
+        opptjeningsvurderingId: UUID,
+    ) : VilkårsgrunnlagElement(vilkårsgrunnlagId, skjæringstidspunkt, inntektsgrunnlag, null, opptjeningsvurderingId = opptjeningsvurderingId) {
```

Overstyring av arbeidsforhold kan endre opptjeningen, og må derfor gi ny id. Dette ble oppdaget med en feilende test etter at V345 var i prod:

`sykepenger-model/.../person/VilkårsgrunnlagHistorikk.kt` ([`a220fa8`](https://github.com/navikt/helse-spleis/commit/a220fa82ba1b768842a0b6558e03e8c0370bfce8))

```diff
             opptjening = hendelse.overstyr(opptjening!!).also {
                 subsumsjonslogg.logg(it.subsumsjon)
             },
+            nyOpptjeningsvurderingId = UUID.randomUUID(),
             subsumsjonslogg = subsumsjonslogg,
@@
-                opptjeningsvurderingId = opptjeningsvurderingId,
+                opptjeningsvurderingId = nyOpptjeningsvurderingId ?: opptjeningsvurderingId,
```

Feilen ble rettet ved å kjøre den samme deterministiske migreringen på nytt som V346 ([`ae4f266`](https://github.com/navikt/helse-spleis/commit/ae4f266afc73f55e3a77ee81f0d3ee89dc3698ca)):

```diff
+internal class V346OverskrivOpptjeningsvurderingId : JsonMigration(version = 346) {
+    override val description = "Overskriver opptjeningsvurderingId på alle vilkårsgrunnlag"
```

**Hvorfor deterministisk id i migreringen:** spleis-api har bare `SELECT` på tabellene `person`, `person_alias` og `melding` (`sykepenger-mediators/src/main/resources/db/migration/V01__initial.sql:82-85`). API-et leser personen og kjører JSON-migreringene i minnet ved hvert oppslag (`SerialisertPerson.tilPersonDto()`), men lagrer aldri resultatet. Spleis lagrer først den migrerte personen når personen behandler en ny hendelse. Fram til det migrerer API-et personen på nytt hver gang. En tilfeldig id ville da gitt ny id ved hvert oppslag, og ingen andre ville fått den samme id-en som Spleis til slutt lagrer. Med `UUID.nameUUIDFromBytes("$vilkårsgrunnlagId:Opptjening")` får API-et, Spleis og alle senere oppslag samme id.

**Hvorfor tilfeldig id på nye grunnlag:** Nye grunnlag opprettes i Spleis og lagres med en gang. Id-en ligger da fast i databasen før noen leser den.

**Koble på migreringen og innfør tilfeldig id på nye grunnlag i samme commit og samme deploy.** Slik ble det gjort for opptjening. Migreringsklassen ble skrevet først ([`9eb7b5b`](https://github.com/navikt/helse-spleis/commit/9eb7b5b5bac131806ad265229693463677ae42af)), men den ble ikke registrert i `SerialisertPerson` før [`ba7997e`](https://github.com/navikt/helse-spleis/commit/ba7997eed67ee12f8430c929d8c50d9f14a20891). Den commiten la også til tilfeldig id på nye grunnlag og gjorde feltet påkrevd i modellen. Begge rekkefølgene går galt:

- **Migreringen først:** Personer som blir lagret, har skjemaversjon 345. Et grunnlag som opprettes etter det, får ingen id, og migreringen kjører aldri igjen for den personen. Grunnlaget står uten id for alltid, eller til du lager en ny migrering.
- **Tilfeldig id først:** Modellen krever id-en, men gamle grunnlag i databasen har den ikke. Spleis og spleis-api klarer ikke å lese personen før migreringen er på plass. Gjør du feltet valgfritt for å komme rundt det, overskriver migreringen de tilfeldige id-ene senere. Det er bare greit så lenge ingen har brukt dem.

Det er greit å skrive migreringsklassen og testen på forhånd, så lenge den ikke er registrert.

**Ellers viktig i rekkefølgen:**
- Steg 2 må være ferdig før noen bruker id-en. V346 endret id-ene på alle grunnlag, også de tilfeldige id-ene fra de første dagene. Det gikk bra fordi ingen hadde lagret noe med de id-ene ennå. Når den nye appen har begynt å lagre vurderinger, kan du ikke endre id-ene.
- Finn alle hendelser som skal gi ny id før migreringen kjører i prod. For opptjening var det overstyring av arbeidsforhold.

## Steg 3: Eksponer id-en og den gamle vurderingen

**Hva:** Id-en ble lagt i Spanner-DTO ([`0bbd37f`](https://github.com/navikt/helse-spleis/commit/0bbd37fe802171e0a5f68238a1eddc25984aae2a)) og i Speil-responsen ([`471e637`](https://github.com/navikt/helse-spleis/commit/471e6376658781b9f50f75e41bb743468c156db5), [`26a0148`](https://github.com/navikt/helse-spleis/commit/26a014896a49cd2acecb4afa8b90cd32c92965f5)). spleis-api fikk et endepunkt som returnerer Spleis sin egen vurdering for hver id hos en person.

`sykepenger-api/src/main/kotlin/no/nav/helse/spleis/opptjening/OpptjeningApi.kt` ([`ce26986`](https://github.com/navikt/helse-spleis/commit/ce26986e8367afa916ac47cc80d706a084c40c41))

```diff
+internal fun Application.opptjeningApi(personDao: PersonDao) {
+    routing {
+        authenticate {
+            post("/api/opptjeningsvurderinger") {
+                val request = call.receive<PersonRequest>()
+                withContext(Dispatchers.IO) {
+                    val serialisertPerson = personDao.hentPersonFraFnr(request.fødselsnummer.toLong()) ?: throw NotFoundException("Kunne ikke finne person for fødselsnummer")
+                    val innDto = serialisertPerson.tilPersonDto()
+                    val person = Person.gjenopprett(EmptyLog, innDto)
+                    val dto = person.dto()
+                    val opptjeningsvurderinger = dto.vilkårsgrunnlagHistorikk.historikk.flatMap { it.vilkårsgrunnlag }.map {
+                        OpptjeningsvurderingDto.fraVilkårsgrunnlag(it)
+                    }.distinctBy { it.opptjeningsvurderingId }
```

Legg merke til `tilPersonDto()`. Det er her migreringen kjøres i minnet, og derfor må id-en fra steg 2 være deterministisk.

Selvstendige har ikke `opptjening` på grunnlaget, så API-et feilet for dem helt til det fikk egen type ([`7234c73`](https://github.com/navikt/helse-spleis/commit/7234c73d5fe89cb7b96cd565f4d3cd2fe0d06b75)):

```diff
-                val arbeidstakerOpptjening = vilkårsgrunnlag.opptjening ?: error("TODO: Bør fikse at opptjening ikke er nullable for Spleis, det er de aldri i praksis")
-                put("oppfylt", arbeidstakerOpptjening.erOppfylt)
+                val arbeidstakerOpptjening = vilkårsgrunnlag.opptjening
+                if (arbeidstakerOpptjening == null) {
+                    put("type", "SELVSTENDIG")
+                } else {
+                    put("type", "ARBEIDSTAKER")
+                    put("oppfylt", arbeidstakerOpptjening.erOppfylt)
```

Senere fikk API-et et beste anslag på `opprettet`. Det er tidspunktet for historikkinnslaget der id-en først dukker opp ([`d99ba29`](https://github.com/navikt/helse-spleis/commit/d99ba29dbf96d9f50439e1f00fc99b416edd99eb)). sp-vilkarsproving bruker det som `vurdertTidspunkt` når vurderingen migreres.

**Hvorfor:** Den nye appen må kunne svare på en id den ikke har sett før. Endepunktet er reserveløsningen den bruker i steg 5, og kilden for migreringen i steg 8. API-et må dekke alle typer grunnlag (arbeidstaker, selvstendig og Infotrygd), ellers feiler oppslaget for noen personer.

## Steg 4: Kanal for endrede vurderinger

**Hva:** Spleis fikk hendelsen `EndretVurderingPåSkjæringstidspunkt`. Den kopierer vilkårsgrunnlaget med den nye id-en og starter revurdering. En felles river lytter på `endret_forsikringsvurdering` og `endret_opptjeningsvurdering`.

`sykepenger-model/.../person/VilkårsgrunnlagHistorikk.kt` og `Person.kt` ([`d1effe9`](https://github.com/navikt/helse-spleis/commit/d1effe9b1e2bd40b93b888a9af499d43f01921fa))

```diff
+        internal fun håndterEndretVurdering(hendelse: EndretVurderingPåSkjæringstidspunkt, aktivitetslogg: IAktivitetslogg): Grunnlagsdata? {
+            if (this !is Grunnlagsdata) return null.also { aktivitetslogg.info("Kan ikke endre på Infotrygdvilkårsgrunnlag") }
+            return when (val endretVurdering = hendelse.endretVurdering) {
+                is Vurdering.Forsikringsvurdering -> medEndretForsikringsvurdering(endretVurdering, aktivitetslogg)
+                is Vurdering.Opptjeningsvurdering -> medEndretOpptjeningsvurdering(endretVurdering, aktivitetslogg)
+            }
+        }
@@
+        internal fun medEndretOpptjeningsvurdering(opptjeningsvurdering: Vurdering.Opptjeningsvurdering, aktivitetslogg: IAktivitetslogg): Grunnlagsdata? {
+            if (opptjeningsvurderingId == opptjeningsvurdering.id) return null.also { aktivitetslogg.info("Opptjeningsvurderingen er allerede lagt til grunn") }
+            return kopierMed(nyOpptjeningsvurderingId = opptjeningsvurdering.id)
+        }
```

```diff
+    fun håndterEndretVurderingPåSkjæringstidspunkt(eventBus: EventBus, endretVurderingPåSkjæringstidspunkt: EndretVurderingPåSkjæringstidspunkt, aktivitetslogg: IAktivitetslogg) {
+        val aktivitetsloggMedPersonkontekst = registrer(aktivitetslogg, "Behandler endret vurdering på skjæringstidspunkt")
+        val grunnlag = vilkårsgrunnlagFor(endretVurderingPåSkjæringstidspunkt.skjæringstidspunkt) ?: return aktivitetsloggMedPersonkontekst.info("Fant ikke vilkårsgrunnlag på ${endretVurderingPåSkjæringstidspunkt.skjæringstidspunkt}")
+        val nyttGrunnlag = grunnlag.håndterEndretVurdering(endretVurderingPåSkjæringstidspunkt, aktivitetsloggMedPersonkontekst) ?: return
+        lagreVilkårsgrunnlag(nyttGrunnlag)
+        igangsettOverstyring(eventBus, endretVurderingPåSkjæringstidspunkt.revurderingseventyr(), aktivitetsloggMedPersonkontekst)
+        håndterGjenoppta(eventBus, endretVurderingPåSkjæringstidspunkt, aktivitetsloggMedPersonkontekst)
+    }
```

`sykepenger-mediators/.../meldinger/EndretVurderingPåSkjæringstidspunktRiver.kt` ([`ae050bc`](https://github.com/navikt/helse-spleis/commit/ae050bc6efa53c87e8215b4bc9001abd86e714ab))

```diff
+    private val vurderinger = setOf(
+        Forsikringsvudering,
+        Opptjeningsvurdering
+    )
+
+    override val eventNames = vurderinger.map { it.eventName }.toSet()
+
+    override fun validate(message: JsonMessage) {
+        message.requireKey("fødselsnummer")
+        message.require("skjæringstidspunkt", JsonNode::asLocalDate)
+        message.interestedIn("manuellVurdering")
+        message.require(message.vurderingsformat.idPath) { UUID.fromString(it.asText()) }
+    }
```

**Hvorfor:** Når vurderingen ligger i en annen app, kan saksbehandler endre den uten at Spleis vet om det. Spleis må få beskjed for å revurdere. For opptjening kom hendelsen før steg 6, men det var ikke nødvendig.

### Når kan kanalen for endrede vurderinger utsettes

Steg 4 kan vente til den nye appen faktisk skal publisere nye vurderinger. Steg 6 trenger ikke kanalen:

- Resultat-behovet slår opp på id-en som står på grunnlaget, ikke på skjæringstidspunktet. Den nye appen svarer med vurderingen den har lagret for den id-en, eller henter den fra spleis-api. Spleis får altså det samme svaret som før, og beregningen endrer seg ikke.
- Uten kanalen fortsetter Spleis å spørre om den gamle id-en. En ny vurdering i den nye appen får ny id, og Spleis får ikke vite om den.

Kanalen må likevel være i prod før den nye appen lager nye vurderinger i prod:

- Ellers starter ikke en manuell vurdering revurdering i Spleis.
- Meldinger går tapt. sp-medlemskap publiserer `endret_medlemskapsvurdering` fra en outbox og markerer meldingen som sendt så snart den ligger på Kafka (`OutboxPubliseringsjobb`), også når ingen i Spleis lytter. Slike meldinger må sendes på nytt manuelt.

Utsetter du steg 4, må publiseringen av endrede vurderinger være skrudd av i prod, for eksempel bak en toggle, til Spleis har riveren.

## Steg 5: Reserveløsningen i den nye appen

**Hva:** Når sp-vilkarsproving får spørsmål om en id den ikke har lagret, henter den svaret fra Spleis-API-et i steg 3. Appen ble så deployet i prod.

`sp-vilkarsproving/.../infra/kafka/OpptjeningsvurderingResultatRiver.kt` ([`1646375`](https://github.com/navikt/helse-sp-vilkarsproving/commit/1646375c7c330325a617f2b49ae437b471846204))

```diff
+        val vilkårsvurdering =
+            transaksjonProvider.transaksjon {
+                it.vilkårsvurderinger.finn(vilkår, vurderingId)
+            }
+
+        val utfall =
+            vilkårsvurdering?.utfall ?: spleisClient
+                .hentOpptjeningsvurderinger(
+                    fødselsnummer = fødselsnummer,
+                ).find { it.opptjeningsvurderingId == vurderingId }
```

Appen ble deployet i prod 27.08 ([`b2d522d`](https://github.com/navikt/helse-sp-vilkarsproving/commit/b2d522d8420292b625289c1afbbb931ad2592665)).

**Hvorfor:** Alle grunnlag som fantes før steg 7, har id fra migreringen i steg 2, og den nye appen har ikke sett dem. Uten reserveløsningen får behovet i steg 6 ikke svar for disse grunnlagene, og Spleis stopper.

**Viktig i rekkefølgen:** Reserveløsningen må være i prod før Spleis sender resultat-behovet i prod. For opptjening kom reserveløsningen 24.08, og behovet ble skrudd på i prod 27.08, samme dag som appen ble deployet i prod.

## Steg 6: Spleis spør den nye appen om resultatet

**Hva:** Under beregning sender Spleis behovet `OpptjeningsvurderingResultat` med id-en fra vilkårsgrunnlaget, og bruker `ok` fra løsningen. Fram til steg 7 svarte sp-vilkarsproving med reserveløsningen fra steg 5. Resultatet var altså fortsatt Spleis sitt eget.

Spleis sendte behovet behovet bak en toggle, med Spleis sin egen vurdering som reserveløsning:

`sykepenger-mediators/.../utboks/EventBusOversetter.kt` og `sykepenger-model/.../person/Vedtaksperiode.kt` ([`4a93e5e`](https://github.com/navikt/helse-spleis/commit/4a93e5e89fc4577383d437ac28a2bbd47dceef89))

```diff
+            if (Toggle.OpptjeningsResultatBehov.enabled) {
+                Behov(
+                    Behov.Behovstype.OpptjeningsvurderingResultat, mapOf(
+                        "opptjeningsvurderingId" to event.opptjeningsvurderingId
+                    )
+                )
+            } else null,
```

```diff
-        val harOpptjening = harOpptjening(grunnlagsdata)
+        val harOpptjening = ytelser.opptjeningsvurderingResultatOk ?: harOpptjening(grunnlagsdata)
```

Dagen etter ble togglen skrudd på i prod ([`4e6d1ab`](https://github.com/navikt/helse-spleis/commit/4e6d1ab6ac4ee2f78003484ac2ac84fd7ef4f9ab)), og samme dag ble både togglen og reserveløsningen fjernet ([`b969787`](https://github.com/navikt/helse-spleis/commit/b96978799eb100d22aec33202e544fd0036ce3ad)):

```diff
-    private fun harOpptjening(grunnlagsdata: VilkårsgrunnlagHistorikk.VilkårsgrunnlagElement): Boolean {
-        return (grunnlagsdata as? VilkårsgrunnlagHistorikk.Grunnlagsdata)?.opptjening?.harTilstrekkeligAntallOpptjeningsdager() ?: true
-    }
@@
-        val harOpptjening = ytelser.opptjeningsvurderingResultatOk ?: harOpptjening(grunnlagsdata)
+        val harOpptjening = ytelser.opptjeningsvurderingResultatOk
```

```diff
-    internal val opptjeningsvurderingResultatOk : Boolean? = packet["@løsning.${Behovstype.OpptjeningsvurderingResultat.utgåendeNavn}.ok"].
-        takeUnless { it.isMissingOrNull() }?.asBoolean()
+    internal val opptjeningsvurderingResultatOk : Boolean = packet["@løsning.${Behovstype.OpptjeningsvurderingResultat.utgåendeNavn}.ok"].asBoolean()
```

**Hvorfor:** Dette steget tester kjøreveien i prod uten å endre resultatet. Kafka, svartid, tilgang til spleis-api og oppetid for den nye appen blir prøvd ut mens svaret er det samme som før. Det gjør det trygt å bli avhengig av den nye appen før den vurderer noe selv.

**Viktig i rekkefølgen:**
- Reserveløsningen i steg 5 måtte være i prod før togglen ble skrudd på i prod. Ellers ville behovet ikke fått svar for gamle grunnlag.
- Behovet sendes ved hver beregning, ikke bare ved vilkårsprøving. Da plukker Spleis opp endringer i vurderingen ved neste beregning.
- Fra 27.08 kan ikke Spleis beregne uten at sp-vilkarsproving svarer.

## Steg 7: Den nye appen gjør vurderingen

**Hva:** Ved vilkårsprøving sender Spleis behovet `Opptjeningsvurdering` med skjæringstidspunkt og arbeidssituasjon. sp-vilkarsproving vurderer, lagrer vurderingen og svarer med id-en. Spleis lagrer id-en på det nye vilkårsgrunnlaget.

`sykepenger-mediators/.../utboks/EventBusOversetter.kt`, `VilkårsgrunnlagMessage.kt` og `sykepenger-model/.../hendelser/Vilkårsgrunnlag.kt` ([`d8558c8`](https://github.com/navikt/helse-spleis/commit/d8558c8dc7fdf4a16ccb5a703a975cbf652e508e))

```diff
+            if (Toggle.OpptjeningsvurderingBehov.enabled) {
+                Behov(
+                    Behov.Behovstype.Opptjeningsvurdering, mapOf(
+                    "skjæringstidspunkt" to event.skjæringstidspunkt,
+                    "arbeidssituasjon" to when (event.yrkesaktivitetssporing) {
+                        Behandlingsporing.Yrkesaktivitet.Arbeidsledig -> "Arbeidsledig"
+                        is Behandlingsporing.Yrkesaktivitet.Arbeidstaker -> "Arbeidstaker"
+                        Behandlingsporing.Yrkesaktivitet.Frilans -> "Frilans"
+                        Behandlingsporing.Yrkesaktivitet.Selvstendig -> "SelvstendigNæringsdrivende"
+                    }
+                )
+                )
+            } else null,
```

```diff
+    internal val opptjeningsvurderingId = packet["@løsning.${Opptjeningsvurdering.utgåendeNavn}.id"].takeUnless { it.isMissingOrNull() }?.asText()?.toUUID()
```

```diff
-            opptjeningsvurderingId = UUID.randomUUID(),
+            opptjeningsvurderingId = opptjeningsvurderingId ?: UUID.randomUUID(), // TODO: fjern random når vi alltid får
```

Togglen var på i dev i over en måned før den ble skrudd på i prod og fjernet ([`7f0a89f`](https://github.com/navikt/helse-spleis/commit/7f0a89fd90049609bd4944cdc47b707f059b8f50)):

```diff
-            if (Toggle.OpptjeningsvurderingBehov.enabled) {
-                Behov(
+            Behov(
+                Behov.Behovstype.Opptjeningsvurdering, mapOf(
```

Samme dag begynte sp-vilkarsproving å sammenligne sin egen vurdering med Spleis sin. Ved avvik logget appen en feil og brukte svaret fra Spleis ([`a0329a9`](https://github.com/navikt/helse-sp-vilkarsproving/commit/a0329a91d199f415cbe3bf03f7b28a972853cdc9)):

```diff
-                val utfall =
-                    opptjeningsvurdering?.erOk ?: spleisClient
+                val lokalVurdering = opptjeningsvurdering?.erOk
+                val spleisVurdering =
+                    spleisClient
@@
+                var utfall =
+                    lokalVurdering ?: spleisVurdering ?: error("Fant ikke vurdering med id $opptjeningsvurderingId")
+
+                if (lokalVurdering != null && spleisVurdering != null && lokalVurdering != spleisVurdering) {
+                    loggError(
+                        "Uoverensstemmelse mellom lokal vurdering og vurdering fra Spleis. Lokal vurdering: $lokalVurdering, Spleis vurdering: $spleisVurdering",
+                        "opptjeningsvurderingId" to opptjeningsvurderingId.toString(),
+                        "fødselsnummer" to fødselsnummer,
+                    )
+                    utfall = spleisVurdering
+                }
```

Sammenligningen ble fjernet 08.10 med meldingen «Har kjørt i prod en uke uten at vi fant noen diff» ([`f30c7a5`](https://github.com/navikt/helse-sp-vilkarsproving/commit/f30c7a5c89fe66ce008bcc54c415fb4892d0acf8)).

**Hvorfor:** Først her bytter vi hvem som eier vurderingen, og bare for nye grunnlag. Gamle grunnlag har fortsatt id fra steg 2 og svar fra Spleis-API-et. Sammenligningen var mulig fordi Spleis fortsatt regnet ut opptjening selv. I sammenligningsuka vant Spleis ved uenighet, så byttet kunne ikke endre utfallet i en sak før vi visste at vurderingene var like.

## Steg 8: Migrer historikken

**Hva:** sp-vilkarsproving henter alle vurderingene til en person fra Spleis-API-et og lagrer dem som `OVERFOERT_FRA_SPLEIS` eller `OVERFOERT_FRA_INFOTRYGD`. Det skjer når Speil slår opp personen ([`dc66b08`](https://github.com/navikt/helse-sp-vilkarsproving/commit/dc66b08933a38c9702664b1d2c40448c72abae3f)) og når `person_avstemming` kommer på rapiden. Avstemmingen ble først slått på i dev ([`82bd9f4`](https://github.com/navikt/helse-sp-vilkarsproving/commit/82bd9f48c9b22cfe3193d2842f155c0fdac2230a)):

```diff
-           /* PersonAvstemmingRiver(
-                rapidsConnection = this,
-                ...
-            )*/
+            if (!erProdGcp()) {
+                PersonAvstemmingRiver(
+                    rapidsConnection = this,
+                    personAvstemmingService =
+                        PersonAvstemmingService(
+                            transaksjonProvider = transaksjonProvider,
+                            spleisClient = spleisClient,
+                        ),
+                )
+            }
```

Så i prod ([`72fa081`](https://github.com/navikt/helse-sp-vilkarsproving/commit/72fa081d5887378055ad0cbaf9503a519353ab3a)). Den ble skrudd av dagen etter og på igjen 11.10, og antall replikaer ble økt ([`c208306`](https://github.com/navikt/helse-sp-vilkarsproving/commit/c208306)).

```diff
-            if (!erProdGcp()) {
-                PersonAvstemmingRiver(
+            PersonAvstemmingRiver(
```

**Hvorfor sist:** Migreringen leser fra API-et i steg 3 og bruker id-ene fra steg 2, så begge må være stabile først. Den kjører per person når personen dukker opp, ikke alt på én gang. Det sprer lasten, men vær forberedt på at volumet fra `person_avstemming` kan bli høyt. Det var trolig derfor den ble skrudd av og på i prod.

**Steg 7 må være på før migreringen starter, og kan ikke rulles tilbake etterpå.** En person migreres bare én gang. Hvis Spleis igjen lager vurderinger etter migreringen, blir de aldri hentet over, og den nye appen mangler dem. Da må personen slettes fra migreringsloggen i sp-vilkarsproving, slik at personen kan migreres på nytt.

## Steg 9: Id-en i godkjenningsbehovet

**Hva:** `opptjeningsvurderingId` ble lagt i `Godkjenningsbehov` ([`b487c18`](https://github.com/navikt/helse-spleis/commit/b487c184c164dd66b70a6474117f39ebaf311bc3)).

`sykepenger-model/.../person/builders/UtkastTilVedtakBuilder.kt` og `sykepenger-mediators/.../utboks/EventBusOversetter.kt`

```diff
+    private lateinit var opptjeningsvurderingId: UUID
+
+    internal fun opptjeningsvurderingId(opptjeningsvurderingId: UUID) = apply { this.opptjeningsvurderingId = opptjeningsvurderingId }
```

```diff
+                "opptjeningsvurderingId" to "${event.opptjeningsvurderingId}",
```

**Hvorfor:** Da kan vedtaket og dataanalyse vise hvilken vurdering som lå til grunn. Dette kunne vært gjort rett etter steg 2. Det avhenger bare av at id-en finnes.

## Steg 10: Rydd opp

Dette steget er ikke gjort for opptjening ennå. Slik koden ser ut nå:

- Spleis regner fortsatt ut `Opptjening` og lagrer det på `Grunnlagsdata`. API-et i steg 3 bruker det.
- `Vilkårsgrunnlag.kt` faller fortsatt tilbake til `UUID.randomUUID()` hvis løsningen mangler id (`// TODO: fjern random når vi alltid får`).
- sp-vilkarsproving spør fortsatt Spleis-API-et når den ikke finner en id lokalt.

Når alle personer er migrert, kan reserveløsningen i sp-vilkarsproving, API-et i Spleis og utregningen av opptjening i Spleis fjernes. Fjern dem i den rekkefølgen.

### Ikke fjern felter fra personmodellen før migreringen er komplett

Fjerner du et felt fra personmodellen i Spleis, forsvinner dataene fra databasen neste gang personen lagres. Grunnen er hvordan Spleis leser og skriver personen:

- `serdeObjectMapper` ignorerer ukjente felter når JSON-en leses (`.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` i `sykepenger-serde/src/main/kotlin/no/nav/helse/serde/ObjectMappers.kt:14`). Et felt som ikke finnes i `PersonData` lenger, blir hoppet over uten feil.
- Spleis lagrer hele personen på nytt fra modellen: `UPDATE person SET skjema_versjon=:skjemaversjon, data=:data WHERE id=:personId` (`sykepenger-mediators/src/main/kotlin/no/nav/helse/spleis/db/PersonDao.kt:249`). Det som ikke ble lest inn, blir ikke skrevet ut igjen.

Personen trenger bare å behandle en hvilken som helst hendelse etter at feltet er fjernet, så er verdien borte. For opptjening gjelder det `opptjening` på `Grunnlagsdata`. Spleis-API-et i steg 3 lager svaret fra dette feltet, så sp-vilkarsproving kan ikke lenger migrere en person som ikke er migrert ennå.

Før du fjerner et felt, må du vite at alle personer er migrert til den nye appen. `person_avstemming` sendes for hver person omtrent hver 28. dag (`(1 + mod(fnr, 28)) = :dag` i `jobs/src/main/kotlin/no/nav/helse/spleis/jobs/App.kt:349`). Én hel runde med avstemming i prod uten feil er derfor et minimum. Kontroller deretter i databasen til den nye appen at antall migrerte personer stemmer med antall personer i Spleis som har vilkårsgrunnlag. Undersøk avvik før du rydder.

Fjern også i riktig rekkefølge. Først reserveløsningen i den nye appen, så API-et i Spleis, og til slutt feltet i modellen. Da er det ingen som leser feltet når det forsvinner.

## Generell oppskrift

Oppskriften passer for alle vilkår der Spleis lagrer et resultat på vilkårsgrunnlaget, og en ny app skal overta det.

1. **Bygg den nye appen** ([steg 1](#steg-1-bygg-den-nye-appen)). Deploy den i dev. Den skal kunne svare på to behov: ett som lager en vurdering og returnerer id-en, og ett som returnerer resultatet for en id.
2. **Id på alle vilkårsgrunnlag** ([steg 2](#steg-2-id-på-alle-vilkårsgrunnlag)). Bruk en deterministisk id i JSON-migreringen, for eksempel `UUID.nameUUIDFromBytes("$vilkårsgrunnlagId:<Vilkår>")`. Registrer migreringen og gi nye grunnlag tilfeldig id i samme commit og samme deploy. Ta med Infotrygd-grunnlag. Finn alle hendelser i `kopierMed` som skal gi ny id, før migreringen går i prod.
3. **Eksponer id-en og den gamle vurderingen** ([steg 3](#steg-3-eksponer-id-en-og-den-gamle-vurderingen)). Legg id-en i Spanner og Speil. Lag et endepunkt i spleis-api som returnerer vurderingen for alle id-er til en person, med `opprettet` og hvilken kilde den kommer fra. Gi den nye appen tilgang.
4. **Kanal for endrede vurderinger** ([steg 4](#steg-4-kanal-for-endrede-vurderinger)). Lag `endret_<vilkår>vurdering` i `EndretVurderingPåSkjæringstidspunkt`, slik at grunnlaget får ny id og blir revurdert. Steget kan vente til den nye appen faktisk skal publisere nye vurderinger, men må være i prod før det skjer (se [Når kan kanalen utsettes](#når-kan-kanalen-for-endrede-vurderinger-utsettes)).
5. **Reserveløsningen i den nye appen** ([steg 5](#steg-5-reserveløsningen-i-den-nye-appen)). Når id-en er ukjent, henter appen svaret fra Spleis-API-et. Deploy i prod før steg 6 skrus på i prod.
6. **Spleis spør den nye appen om resultatet** ([steg 6](#steg-6-spleis-spør-den-nye-appen-om-resultatet)). Send `<Vilkår>vurderingResultat` ved beregning, bak toggle. Bruk svaret i stedet for Spleis sitt eget. Dev først, så prod. Fjern toggle og reserveløsning i Spleis når det går bra.
7. **Den nye appen gjør vurderingen** ([steg 7](#steg-7-den-nye-appen-gjør-vurderingen)). Send `<Vilkår>vurdering` ved vilkårsprøving, bak toggle. Lagre id-en på det nye grunnlaget. Sammenlign med Spleis sitt svar i en periode, og la Spleis vinne ved avvik.
8. **Migrer historikken** ([steg 8](#steg-8-migrer-historikken)) ved oppslag og med `person_avstemming`.
9. **Id-en i godkjenningsbehovet** ([steg 9](#steg-9-id-en-i-godkjenningsbehovet)). Legg id-en i `Godkjenningsbehov`. Det kan gjøres når som helst etter steg 2.
10. **Rydd opp** ([steg 10](#steg-10-rydd-opp)) i Spleis og i den nye appen. Ikke fjern feltet med den gamle vurderingen fra personmodellen før alle personer er migrert. Da forsvinner dataene fra databasen (se [Ikke fjern felter fra personmodellen før migreringen er komplett](#ikke-fjern-felter-fra-personmodellen-før-migreringen-er-komplett)).

Reglene som gjør rekkefølgen trygg:

- Ingen leser id-en før den er stabil. Det er bare trygt å endre id-er før den nye appen har lagret noe.
- Hvert steg som gjør Spleis avhengig av den nye appen, gir først samme svar som før.
- Det nye svaret brukes bare for nye grunnlag. Det gamle svaret ligger igjen i Spleis til historikken er migrert.
- Alt som endrer oppførsel, ligger bak en toggle og går i dev før prod.
- Felter i personmodellen fjernes ikke før migreringen er komplett. Spleis skriver hele personen på nytt fra modellen, så et felt som er borte fra modellen, forsvinner også fra databasen.
- Én id gir alltid samme svar. Se under.

### Kontrakten: én id gir alltid samme svar

Den nye appen skal aldri endre svaret for en id den har gitt ut. En ny vurdering av samme skjæringstidspunkt får ny id. Hele modellen bygger på dette.

Id-en ligger på vilkårsgrunnlaget, og alle perioder med samme skjæringstidspunkt bruker samme grunnlag. Spleis spør om resultatet ved hver beregning. En forlengelse spør derfor om den samme id-en som perioden før, og får samme svar. Reserveløsningen gir også samme svar: Spleis bruker statusen på grunnlaget, og den nye appen henter ukjente id-er fra spleis-api, som leser den samme statusen.

Det finnes to måter å få et nytt svar på, og begge gir ny id:

- **Nytt vilkårsgrunnlag**, for eksempel når skjæringstidspunktet flytter seg. Spleis vilkårsprøver på nytt, og grunnlaget får ny id.
- **Ny vurdering i den nye appen.** Appen publiserer `endret_<vilkår>vurdering` med den nye id-en. Spleis lager et nytt grunnlag med id-en og revurderer alle perioder fra skjæringstidspunktet, også de som er utbetalt (se [steg 4](#steg-4-kanal-for-endrede-vurderinger)).

Bryter appen kontrakten, kan perioder med samme grunnlag få ulike svar uten at Spleis merker det. Eksempel: den første perioden er utbetalt med `JA`, og så svarer appen `NEI` på samme id i forlengelsen. Spleis beregner bare perioder som ikke er ferdig behandlet (`perioderDetSkalBeregnesUtbetalingFor`). Forlengelsen får derfor avviste dager, mens den utbetalte perioden blir stående. Ingenting starter revurdering, fordi id-en på grunnlaget er den samme. `medEndret<Vilkår>vurdering` ignorerer i tillegg en endring som kommer med en id som allerede er lagt til grunn.

sp-medlemskap håndhever kontrakten:

- `PostgresMedlemskapsvurderingRepository.lagre` setter bare inn rader. Lagrer appen samme id to ganger, kaster den «Vurderinger er immutable».
- `Medlemskapsvurdering.automatisk` og `avSaksbehandler` lager alltid ny id.
- `MedlemskapsvurderingResultatRiver` slår opp med `finn(id)` og aldri med `gjeldende(fødselsnummer, skjæringstidspunkt)`. Et oppslag på gjeldende vurdering ville gitt et nytt svar på gammel id så snart en ny vurdering var lagret.

## Steg for steg for medlemskap

Medlemskap er ikke helt likt opptjening. Spleis vurderer ikke medlemskap selv. Spleis sender behovet `Medlemskap`, sparkel-medlemskap svarer, og Spleis lagrer svaret som `medlemskapstatus` på `Grunnlagsdata`. Statusen brukes tre steder, og alle tre må få svaret fra sp-medlemskap:

| Hvor | Hva | Fil |
|---|---|---|
| Vilkårsprøving | Varsel `RV_MV_2` ved `Nei` og `RV_MV_1` ved `UavklartMedBrukerspørsmål` | `sykepenger-model/.../hendelser/Medlemskapsvurdering.kt` |
| Beregning | `erMedlemAvFolketrygden = medlemskapstatus != Nei` | `sykepenger-model/.../person/Vedtaksperiode.kt` (rundt linje 1640–1663) |
| Speil | `oppfyllerKravOmMedlemskap` | `sykepenger-api/.../speil/builders/VilkårsgrunnlagBuilder.kt` (rundt linje 193) |

Status for hvert steg:

| Steg | Status |
|---|---|
| 1. Bygg den nye appen | Stort sett gjort |
| 2. Id på alle vilkårsgrunnlag | Gjort ([`e19958c`](https://github.com/navikt/helse-spleis/commit/e19958c02c)) |
| 3. Eksponer id-en og den gamle vurderingen | Endepunktet er laget. Spanner og Speil gjenstår |
| 4. Kanal for endrede vurderinger | Laget i Spleis. Bytter bare id-en, ikke `medlemskapstatus` |
| 5. Reserveløsningen i den nye appen | Laget i sp-medlemskap. Ikke i prod |
| 6. Spleis spør den nye appen om resultatet | Laget bak toggle, på i dev |
| 7. Den nye appen gjør vurderingen | Laget i Spleis bak toggle, på i dev. Sender både `Medlemskap` og `Medlemskapsvurdering` |
| 8. Migrer historikken | `PersonAvstemmingRiver` finnes, men er kommentert ut i `App.kt` |
| 9. Id-en i godkjenningsbehovet | Ikke startet |
| 10. Rydd opp | Ikke startet |

### 1. Bygg den nye appen

Dette er stort sett gjort. sp-medlemskap svarer på `Medlemskapsvurdering` og `MedlemskapsvurderingResultat`, har `PersonAvstemmingRiver` og en `SpleisClient` mot endepunktet i steg 3. Se [README](../README.md).

### 2. Id på alle vilkårsgrunnlag

- Lag JSON-migreringen `V348LeggeTilMedlemskapsvurderingId` (V347 er siste nå, sjekk før du lager den). Bruk `UUID.nameUUIDFromBytes("$vilkårsgrunnlagId:Medlemskap")`, også for Infotrygd-grunnlag.
- Legg `medlemskapsvurderingId` i `VilkårsgrunnlagElement`, inn- og ut-DTO-ene, `PersonData` og `PersonDataBuilder`.
- Gi nye grunnlag `UUID.randomUUID()` i `Vilkårsgrunnlag.kt`. Gjør det i samme commit som du registrerer migreringen i `SerialisertPerson`. Migreringsklassen og testen kan gjerne komme i en egen commit før, så lenge migreringen ikke er registrert.
- La `kopierMed` beholde `medlemskapsvurderingId`. Medlemskap trenger ikke `nyMedlemskapsvurderingId` ennå (se under).
- Skriv en test tilsvarende `OpptjeningsvurderingIdTest` før migreringen går i prod.

Id-en i migreringen må være deterministisk fordi spleis-api bare kan lese og kjører migreringen i minnet ved hvert oppslag. Se [steg 2](#steg-2-id-på-alle-vilkårsgrunnlag).

Steget er gjort i [`e19958c`](https://github.com/navikt/helse-spleis/commit/e19958c02c). Testene ligger i `sykepenger-model/.../spleis/e2e/MedlemskapsvurderingIdTest.kt`.

#### Hvorfor medlemskap ikke får samme feil som V345

V346 måtte skrive opptjenings-id-ene på nytt fordi overstyring av arbeidsforhold endret opptjeningen i `kopierMed`, men beholdt den gamle id-en. Statusen og id-en pekte da på ulike vurderinger.

For medlemskap kan ikke dette skje i dag:

- `kopierMed` har ikke noe parameter for medlemskap. Den kopierer alltid både `medlemskapstatus` og `medlemskapsvurderingId` fra det gamle grunnlaget.
- Medlemskapet endres bare når medlemskapstjenesten svarer på nytt. Svaret kommer i `VilkårsgrunnlagMessage`, og `Vilkårsgrunnlag.valider()` lager da et nytt grunnlag med ny tilfeldig id. Status og id byttes alltid samtidig.
- `EndretVurderingPåSkjæringstidspunkt` bytter bare `medlemskapsvurderingId`, ikke `medlemskapstatus`. Se [steg 4](#4-kanal-for-endrede-vurderinger).

Overstyring av arbeidsforhold spør ikke medlemskapstjenesten på nytt, så statusen er den samme. Da er det riktig å beholde id-en, fordi den peker på vurderingen som faktisk brukes. Testene i `MedlemskapsvurderingIdTest` låser begge tilfellene:

- `Overstyring av arbeidsforhold beholder medlemskapsvurderingId`: kopien beholder id-en.
- `nytt skjæringstidspunkt gir ny medlemskapsvurderingId`: Spleis vilkårsprøver på nytt og lager ny id.

Den andre testen går gjennom `Vilkårsgrunnlag` og ikke gjennom `kopierMed`. Den viser at Spleis spør på nytt når skjæringstidspunktet flytter seg, men den ville ikke fanget en feil som den bak V345.

Ny id i `kopierMed` blir først aktuelt i [steg 4](#4-kanal-for-endrede-vurderinger). Se også forbeholdet om nullable id i [steg 7](#7-den-nye-appen-gjør-vurderingen).

### 3. Eksponer id-en og den gamle vurderingen

- Legg id-en i Spanner-DTO og i Speil-responsen.
- Lag `POST /api/medlemskapsvurderinger` i spleis-api med kontrakten sp-medlemskap venter: `{ medlemskapsvurderinger: [{ medlemskapsvurderingId, opprettet, skjæringstidspunkt, kilde: SPLEIS|INFOTRYGD, medlemskapstatus?: JA|NEI|VET_IKKE|UAVKLART_MED_BRUKERSPORSMAAL }] }`. Bruk logikken for `opprettet` fra [`d99ba29`](https://github.com/navikt/helse-spleis/commit/d99ba29dbf96d9f50439e1f00fc99b416edd99eb).
- Gi sp-medlemskap tilgang inn til spleis-api i `.nais/spleis-api.yaml`.

Endepunktet og tilgangen er laget i `sykepenger-api/.../spleis/medlemskap/MedlemskapApi.kt`, med tester i `MedlemskapsvurderingApiTest` og `MedlemskapsvurderingerTest`. Spanner og Speil gjenstår.

### 4. Kanal for endrede vurderinger

- Legg `Medlemskapsvurdering` til i `EndretVurderingPåSkjæringstidspunktMessage.Vurderingsformat` og `Vurdering`.
- Lag `medEndretMedlemskapsvurdering` i `Grunnlagsdata`. Gjør som `medEndretOpptjeningsvurdering`: kopier med id-en fra sp-medlemskap, og gjør ingenting hvis id-en allerede er lagt til grunn.
- Dette er første gang `kopierMed` kan få nytt medlemskap. Send ny status og ny id inn sammen, helst som ett parameter, så det er umulig å endre statusen uten å bytte id-en. Ellers kan du gjenta feilen bak V345 og V346.

Steget er laget i Spleis. `EndretVurderingPåSkjæringstidspunktRiver` lytter på `endret_medlemskapsvurdering`, og `medEndretMedlemskapsvurdering` lager et nytt grunnlag med id-en fra sp-medlemskap og starter revurdering med årsaken `ENDRET_MEDLEMSKAPSVURDERING`. Testene ligger i `EndretVurderingPåSkjæringstidspunktTest` og `MessageMediatorTest`.

Eventet har ikke med status, så Spleis bytter bare id-en. `medlemskapstatus` på grunnlaget beholdes. Det er greit fordi beregningen bruker svaret fra `MedlemskapsvurderingResultat` for den nye id-en (steg 6). Med togglen `MEDLEMSKAPSVURDERINGRESULTATBEHOV` av gir revurderingen samme resultat som før, og Speil viser fortsatt statusen på grunnlaget.

Dette må være på plass i prod før sp-medlemskap begynner å lage nye vurderinger og publisere `endret_medlemskapsvurdering` i prod. Det trenger ikke være på plass før steg 6. Se [Når kan kanalen utsettes](#når-kan-kanalen-for-endrede-vurderinger-utsettes).

### 5. Reserveløsningen i den nye appen

- Kontroller at `MedlemskapsvurderingResultatRiver` henter fra Spleis-API-et når id-en er ukjent, og at Infotrygd-grunnlag gir `JA`.
- Kontroller at riveren slår opp på id og aldri på gjeldende vurdering for skjæringstidspunktet (se [kontrakten](#kontrakten-én-id-gir-alltid-samme-svar)).
- Deploy sp-medlemskap i prod med tilgang til rapiden og spleis-api.

### 6. Spleis spør den nye appen om resultatet

Behovet heter `MedlemskapsvurderingResultat` og sendes ved beregning, bak toggle.

- Send behovet i `EventBusOversetter` sammen med `OpptjeningsvurderingResultat`, med `medlemskapsvurderingId` fra grunnlaget.
- Les `svar`, ikke bare `ok`, i `YtelserMessage`. `ok` er bare `false` ved `NEI`, og da forsvinner forskjellen mellom `JA`, `UAVKLART` og `UAVKLART_MED_BRUKERSPORSMAAL`.
- Bruk svaret til `erMedlemAvFolketrygden` i `Vedtaksperiode`.
- Flytt varslene `RV_MV_1` og `RV_MV_2` til beregningen. Da får saksbehandler riktig varsel også når vurderingen er endret i sp-medlemskap. Pass på at varslene ikke kommer dobbelt mens grunnlaget fortsatt gir dem ved vilkårsprøving.
- La `medlemskapstatus` på grunnlaget være reserveløsning mens togglen er av: `ytelser.medlemskapsvurderingResultat ?: grunnlagsdata.medlemskapstatus`.
- Dev først, så prod. Fjern toggle og reserveløsning når det går bra.

Etter dette steget kan Spleis ikke beregne uten svar fra sp-medlemskap.

Behovet, lesingen av svaret og reserveløsningen er laget bak togglen `MEDLEMSKAPSVURDERINGRESULTATBEHOV`. Den står som `"false"` i `.nais/spleis.dev-gcp.yaml`. Når svaret er ulikt statusen på grunnlaget, skriver Spleis en info-melding i aktivitetsloggen. Testene ligger i `MedlemskapE2E`, `YtelserRiverMappingTest` og `BehovkontraktTest`.

Varslene er flyttet bak den samme togglen. `ok` er ikke nok til å velge varsel, så Spleis leser hele `svar`:

- Med togglen på gir vilkårsprøvingen ingen medlemskapsvarsler. Beregningen gir `RV_MV_2` ved `NEI` og `RV_MV_1` ved `UAVKLART_MED_BRUKERSPORSMAAL`, ut fra svaret fra sp-medlemskap. Mangler svaret, brukes statusen på grunnlaget.
- Med togglen av gir vilkårsprøvingen varslene som før, og beregningen gir ingen.

Varslene kommer derfor bare én gang, og de følger statusen beregningen faktisk bruker.

### 7. Den nye appen gjør vurderingen

Behovet heter `Medlemskapsvurdering` og sendes ved vilkårsprøving, bak toggle.

- Send `Medlemskapsvurdering { skjæringstidspunkt, medlemskapPeriodeFom, medlemskapPeriodeTom }` i tillegg til `Medlemskap`, og lagre `@løsning.Medlemskapsvurdering.id` på det nye grunnlaget.
- Behold `Medlemskap` så lenge statusen på grunnlaget trengs. sp-medlemskap svarer bare med `id`, men `medlemskapstatus` på grunnlaget er påkrevd og brukes av Speil, spleis-api og reserveløsningen i steg 6.
- Gjør som [`d8558c8`](https://github.com/navikt/helse-spleis/commit/d8558c8dc7fdf4a16ccb5a703a975cbf652e508e) gjorde for opptjening: Legg til `medlemskapsvurderingId: UUID?` i `Vilkårsgrunnlag`, og bruk `medlemskapsvurderingId ?: UUID.randomUUID()`. Id-en må være nullable fordi togglen først er på i dev, og fordi et behov sendt før togglen ble slått på, kan få en løsning uten id. Feltet på grunnlaget er fortsatt påkrevd. Fjern reserveverdien når togglen er på i prod og alle gamle behov er besvart.

Steget er laget i Spleis bak togglen `MEDLEMSKAPSVURDERINGBEHOV`, som står som `"false"` i `.nais/spleis.dev-gcp.yaml`:

- `EventBusOversetter` sender `Medlemskapsvurdering` sammen med `Medlemskap` når togglen er på.
- `VilkårsgrunnlagRiver` er `interestedIn` i `@løsning.Medlemskapsvurdering.id`. Behovet står ikke i riverens behovliste, så løsninger uten det nye behovet blir fortsatt plukket opp.
- `Vilkårsgrunnlag` har fått `medlemskapsvurderingId: UUID?` og bruker `medlemskapsvurderingId ?: UUID.randomUUID()`.
- Testene ligger i `MedlemskapsvurderingIdTest`, `VilkårsgrunlagRiverMappingTest` og `BehovkontraktTest`.

Med togglen på kan statusen på grunnlaget og id-en peke på ulike vurderinger. Statusen kommer fra et nytt oppslag i sparkel-medlemskap, mens sp-medlemskap gjenbruker den gjeldende vurderingen hvis den alt har en for skjæringstidspunktet. Når steg 6 er på, bruker beregningen svaret for id-en, og Spleis skriver en info-melding i aktivitetsloggen ved avvik. Speil og spleis-api viser fortsatt statusen på grunnlaget til steg 3 og Speil leser fra sp-medlemskap.

Her skiller medlemskap seg fra opptjening. For opptjening regnet Spleis fortsatt ut svaret selv, så sammenligningen var gratis. For medlemskap betyr sammenligning at både Spleis og sp-medlemskap sender `Medlemskap` til sparkel-medlemskap, altså to oppslag mot LovMe for hver vilkårsprøving. Velg en av disse:

- Avklar med LovMe at dobbelt volum er greit i en periode, og sammenlign som i [steg 7](#steg-7-den-nye-appen-gjør-vurderingen). Spleis sender allerede begge behovene når togglen er på. Skal Spleis vinne ved avvik, må sammenligningen ligge i `MedlemskapsvurderingResultatRiver` i sp-medlemskap, slik [`a0329a9`](https://github.com/navikt/helse-sp-vilkarsproving/commit/a0329a91d199f415cbe3bf03f7b28a972853cdc9) gjorde for opptjening.
- Hopp over sammenligningen. sp-medlemskap gjør det samme oppslaget som Spleis gjør i dag, så risikoen er mindre enn for opptjening, der vurderingslogikken var skrevet på nytt.

### 8. Migrer historikken

Slå på `PersonAvstemmingRiver` i dev, så i prod. Følg med på volumet og antall replikaer.

### 9. Id-en i godkjenningsbehovet

Som i [steg 9](#steg-9-id-en-i-godkjenningsbehovet). Kan gjøres når som helst etter steg 2.

### 10. Rydd opp

Ikke start før du vet at alle personer er migrert til sp-medlemskap. La `person_avstemming` gå minst én hel runde (omtrent 28 dager) i prod uten feil, og kontroller at antall migrerte personer i sp-medlemskap stemmer med Spleis. Fjerner du `medlemskapstatus` fra modellen for tidlig, forsvinner verdien fra databasen neste gang personen lagres, og Spleis-API-et kan ikke lenger gi sp-medlemskap svaret (se [Ikke fjern felter fra personmodellen før migreringen er komplett](#ikke-fjern-felter-fra-personmodellen-før-migreringen-er-komplett)).

Fjern i denne rekkefølgen:

1. Fjern reserveløsningen mot Spleis-API-et i sp-medlemskap.
2. Fjern `POST /api/medlemskapsvurderinger` i spleis-api.
3. Fjern `medlemskapstatus` fra `Grunnlagsdata`, `PersonData` og DTO-ene, og fjern `Medlemskapsvurdering.validert` og `Behovstype.Medlemskap` i Spleis. Først nå er det trygt at verdien forsvinner fra databasen.
4. Fjern tilfeldig id som reserveløsning i `Vilkårsgrunnlag.kt`.
