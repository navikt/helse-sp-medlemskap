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
4. Når løsningen `@løsning.Medlemskap.resultat.svar` kommer, lagrer appen vurderingen og svarer på det opprinnelige behovet med vurderingens id.
5. Spleis sender `MedlemskapsvurderingResultat { medlemskapsvurderingId }` og får tilbake `{ "svar": "JA" | "NEI" | "UAVKLART" | "UAVKLART_MED_BRUKERSPORSMAAL", "ok": true | false }`. `ok` er `false` bare når svaret er `NEI`.

Vurderinger som Spleis har gjort før denne appen fantes, hentes fra Spleis ved behov og lagres som `OVERFOERT_FRA_SPLEIS` eller `OVERFOERT_FRA_INFOTRYGD`.


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
- **Spleis må endres** for å sende `Medlemskapsvurdering` og `MedlemskapsvurderingResultat` i stedet for å slå opp `Medlemskap` selv. Den endringen er ikke gjort.

## Bygg og test

```sh
./gradlew build
```

Testene bruker Testcontainers og trenger Docker.
