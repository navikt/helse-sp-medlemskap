# helse-sp-medlemskap

Vurderer medlemskapsvilkåret (folketrygdloven kapittel 2) for sykepenger. Appen tar over medlemskapsoppslaget som Spleis gjør i dag, på samme måte som sp-vilkarsproving har tatt over opptjeningsvurderingen.

Appen slår opp medlemskap gjennom behovet `Medlemskap`, som sparkel-medlemskap løser. Hver vurdering lagres uforanderlig og får en egen id. Spleis får id-en og kan senere spørre om resultatet.

## Flyt

1. Spleis sender behovet `Medlemskapsvurdering`:
   ```json
   { "@behov": ["Medlemskapsvurdering"], "fødselsnummer": "...", "vedtaksperiodeId": "...",
     "Medlemskapsvurdering": { "skjæringstidspunkt": "2018-02-01", "medlemskapPeriodeFom": "2018-02-01", "medlemskapPeriodeTom": "2018-02-28" } }
   ```
2. Finnes det allerede en vurdering for personen og skjæringstidspunktet, svarer appen med en gang med `@løsning.Medlemskapsvurdering.id`.
3. Hvis ikke, starter appen en medlemskapsprøving og sender behovet `Medlemskap` til sparkel-medlemskap. Det opprinnelige behovet ligger med i `opprinneligBehov`.
4. Når løsningen `@løsning.Medlemskap.resultat.svar` kommer, lagrer appen vurderingen og svarer på det opprinnelige behovet med vurderingens id. Eventuelle `resultat.aarsaker` (`[{ regelId, forklaringPaaUavklart }]`) lagres i vurderingens kilde. Feltet kan mangle eller være `null`, men har det feil struktur, avvises løsningen.
5. Spleis sender `MedlemskapsvurderingResultat { medlemskapsvurderingId }` og får tilbake `{ "svar": "JA" | "NEI" | "UAVKLART" | "UAVKLART_MED_BRUKERSPORSMAAL", "ok": true | false }`. `ok` er `false` bare når svaret er `NEI`.

Vurderinger som Spleis har gjort før denne appen fantes, hentes fra Spleis ved behov og lagres som `OVERFOERT_FRA_SPLEIS` eller `OVERFOERT_FRA_INFOTRYGD`.

### Forhåndsvurderinger fra medlemskapstjenesten

Medlemskapstjenesten (LovMe) vurderer førstegangssøknader når de kommer inn, og tar hensyn til svarene bruker har gitt. Den sender vurderingen på `medlemskap.medlemskapsvurderinger`:

```json
{ "soknadId": "<søknadens id hos Flex>", "fnr": "...", "speilSvar": "JA", "aarsaker": [{ "regelId": "REGEL_3", "forklaringPaaUavklart": "..." }],
  "gyldigForSkjaeringstidspunktFom": "2018-01-01", "gyldigForSkjaeringstidspunktTom": "2018-01-31" }
```

Spør vi medlemskapstjenesten direkte før den er ferdig med søknaden, kan vi få `UAVKLART_MED_BRUKERSPORSMAAL` der forhåndsvurderingen ville gitt `JA`. Appen kan derfor vente på forhåndsvurderingen:

1. Appen lagrer alle forhåndsvurderinger i tabellen `forhandsvurdering`. `gyldigForSkjaeringstidspunktFom` og `gyldigForSkjaeringstidspunktTom` sier hvilke skjæringstidspunkter vurderingen gjelder for. `soknadId` lagres bare for sporing.
2. Skal behovet starte en ny prøving for en arbeidstaker (`yrkesaktivitetstype` er `ARBEIDSTAKER`), ser appen etter en forhåndsvurdering for personen der skjæringstidspunktet er innenfor denne perioden, med begge endepunktene. Finnes flere, brukes den sist mottatte. Medlemskapstjenesten forhåndsvurderer bare førstegangssøknader fra arbeidstakere, ikke påfølgende søknader eller korrigeringer. Er første søknad avsluttet uten utbetaling, gjelder forhåndsvurderingen likevel det samme skjæringstidspunktet.
3. Er forhåndsvurderingen allerede mottatt, svarer appen med en gang. Hvis ikke, venter prøvingen på forhåndsvurderingen, og behovet lagres i `ventende_behov`. Kommer behovet på nytt mens prøvingen venter, erstatter det nye behovet det gamle.
4. `VentendeBehovjobb` (leader, hvert 5. sekund) svarer på det ventende behovet når forhåndsvurderingen kommer. Har den ikke kommet innen fristen, sender jobben behovet `Medlemskap`, og flyten fortsetter som over.

For andre yrkesaktiviteter spør appen medlemskapstjenesten direkte. En vurdering fra en forhåndsvurdering har `forhåndsvurdertSøknadId` i kilden.

Funksjonen er av til `FORHANDSVURDERING_VENTETID` (ISO-8601, for eksempel `PT5M`) er satt. `KAFKA_EXTRA_TOPIC=medlemskap.medlemskapsvurderinger` må også være satt, ellers kommer forhåndsvurderingene aldri.


## REST-API for saksbehandler

| Endepunkt | Hva |
|---|---|
| `GET /api/personer/{personId}/vilkarsvurderinger?medlemskapsvurderingId=` | Henter en vurdering med kilde |
| `POST /api/personer/{personId}/vilkarsvurderinger/manuell` | Manuell vurdering: `{ skjæringstidspunkt, status: JA\|NEI, fritekstbegrunnelse, journalpostId }` |
| `POST /api/personer/{personId}/vilkarsvurderinger/automatisk` | Slår opp medlemskap på nytt for et skjæringstidspunkt |
| `GET /api/personer/{personId}/medlemskapsvurderinger/historikk` | Historikk over vurderinger |
| `GET /api/personer/{personId}/saksbehandlerhandlinger/{handlingId}/visning` | Visning av en saksbehandlerhandling |

Når en vurdering endres, publiserer appen gjennom outboxen først en `saksbehandlerhandling` (`MANUELT_VURDERT_MEDLEMSKAP` eller `NY_AUTOMATISK_MEDLEMSKAPSVURDERING`) og deretter `endret_medlemskapsvurdering`.

## Antakelser og avhengigheter

- **Spleis-endepunkt:** Appen kaller `POST /api/medlemskapsvurderinger` i Spleis for å hente eldre vurderinger. Endepunktet finnes ikke ennå. Kontrakten vi antar er `{ medlemskapsvurderinger: [{ medlemskapsvurderingId, opprettet, skjæringstidspunkt, kilde: SPLEIS|INFOTRYGD, medlemskapstatus?: JA|NEI|VET_IKKE|UAVKLART_MED_BRUKERSPORSMAAL }] }`.
- **Infotrygd:** Vurderinger fra Infotrygd regnes som `JA`.
- **vedtaksperiodeId:** sparkel-medlemskap krever `vedtaksperiodeId`. Når saksbehandler ber om en ny automatisk vurdering, finnes det ingen vedtaksperiode, så appen sender medlemskapsprøvingens id i stedet.
- **Forhåndsvurderinger:** Appen trenger lesetilgang til `medlemskap.medlemskapsvurderinger` fra team medlemskap. Medlemskapstjenesten har ennå ikke datoene i meldingen. Flytter Spleis skjæringstidspunktet utenfor perioden, kommer det ingen forhåndsvurdering som passer, og appen spør medlemskapstjenesten direkte når fristen går ut. Forhåndsvurderinger uten datoene lagres, men brukes aldri. Meldinger med bare én av datoene, eller med fom etter tom, avvises.
- **Spleis må endres** for å sende `Medlemskapsvurdering` og `MedlemskapsvurderingResultat` i stedet for å slå opp `Medlemskap` selv. Den endringen er ikke gjort.

## Bygg og test

```sh
./gradlew build
```

Testene bruker Testcontainers og trenger Docker.
