-- Forhåndsvurderinger medlemskapstjenesten (LovMe) gjør asynkront når en førstegangssøknad kommer inn,
-- lest fra topicet medlemskap.medlemskapsvurderinger. Vurderingen gjelder skjæringstidspunkter fra og
-- med gyldig_fom til og med gyldig_tom. Datoene mangler på meldinger fra før LovMe begynte å sende
-- dem. Den nyeste vurderingen som gjelder, brukes.
CREATE TABLE forhandsvurdering
(
    løpenummer             BIGSERIAL PRIMARY KEY,
    søknad_id              UUID        NOT NULL,
    fødselsnummer          VARCHAR(11) NOT NULL,
    gyldig_fom             DATE,
    gyldig_tom             DATE,
    svar                   TEXT        NOT NULL CHECK (svar IN ('JA', 'NEI', 'UAVKLART', 'UAVKLART_MED_BRUKERSPORSMAAL')),
    aarsaker               JSONB,
    melding                JSONB       NOT NULL,
    mottatt                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((gyldig_fom IS NULL AND gyldig_tom IS NULL) OR (gyldig_fom IS NOT NULL AND gyldig_tom IS NOT NULL AND gyldig_fom <= gyldig_tom))
);

CREATE INDEX idx_forhandsvurdering_fodselsnummer
    ON forhandsvurdering (fødselsnummer, løpenummer DESC);

-- Behov for medlemskapsvurdering fra Spleis som holdes tilbake mens prøvingen venter på en
-- forhåndsvurdering. Raden fjernes når prøvingen har fått forhåndsvurderingen, eller når fristen
-- har gått ut og medlemskapstjenesten spørres direkte i stedet.
CREATE TABLE ventende_behov
(
    medlemskapsproving_id UUID PRIMARY KEY REFERENCES medlemskapsproving (id) ON DELETE CASCADE,
    fødselsnummer         VARCHAR(11) NOT NULL,
    vedtaksperiode_id     TEXT        NOT NULL,
    opprinnelig_behov     JSONB       NOT NULL,
    frist                 TIMESTAMPTZ NOT NULL,
    opprettet             TIMESTAMPTZ NOT NULL DEFAULT now(),
    endret                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ventende_behov_opprettet
    ON ventende_behov (opprettet);
