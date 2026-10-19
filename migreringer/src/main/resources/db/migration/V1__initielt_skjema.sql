CREATE TABLE medlemskapsproving
(
    løpenummer              BIGSERIAL PRIMARY KEY,
    id                      UUID        NOT NULL UNIQUE,
    fødselsnummer           VARCHAR(11) NOT NULL,
    skjæringstidspunkt      DATE        NOT NULL,
    oppslag_fom             DATE        NOT NULL,
    oppslag_tom             DATE        NOT NULL,
    startet                 TIMESTAMPTZ NOT NULL,
    tilstand                TEXT        NOT NULL,
    utestående_behov        TEXT,
    medlemskapsvurdering_id UUID,
    opprettet               TIMESTAMPTZ NOT NULL DEFAULT now(),
    endret                  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT medlemskapsproving_oppslagsperiode_er_gyldig CHECK (oppslag_fom <= oppslag_tom),
    CONSTRAINT medlemskapsproving_tilstand_er_konsistent CHECK (
        (tilstand = 'VENTER_PÅ_GRUNNLAG' AND utestående_behov IS NOT NULL AND medlemskapsvurdering_id IS NULL) OR
        (tilstand = 'FULLFØRT' AND utestående_behov IS NULL AND medlemskapsvurdering_id IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uix_medlemskapsproving_aktiv
    ON medlemskapsproving (fødselsnummer, skjæringstidspunkt)
    WHERE tilstand <> 'FULLFØRT';

CREATE INDEX idx_medlemskapsproving_noekkel
    ON medlemskapsproving (fødselsnummer, skjæringstidspunkt, løpenummer DESC);

CREATE TABLE medlemskapsvurdering
(
    id                 UUID PRIMARY KEY,
    fødselsnummer      VARCHAR(11) NOT NULL,
    skjæringstidspunkt DATE        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('JA', 'NEI', 'UAVKLART', 'UAVKLART_MED_BRUKERSPORSMAAL')),
    vurdert_tidspunkt  TIMESTAMPTZ NOT NULL,
    vurderingskilde    TEXT        NOT NULL CHECK (vurderingskilde IN ('AUTOMATISK', 'SAKSBEHANDLER', 'OVERFOERT_FRA_SPLEIS', 'OVERFOERT_FRA_INFOTRYGD')),
    kilde              JSONB       NOT NULL,
    opprettet          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_medlemskapsvurdering_noekkel
    ON medlemskapsvurdering (fødselsnummer, skjæringstidspunkt, vurdert_tidspunkt DESC, opprettet DESC);

CREATE TABLE outbox
(
    id                  UUID PRIMARY KEY,
    sekvensnummer       BIGINT GENERATED ALWAYS AS IDENTITY,
    melding             JSONB     NOT NULL,
    fodselsnummer       TEXT      NOT NULL,
    opprettet           TIMESTAMP NOT NULL DEFAULT now(),
    publisert_tidspunkt TIMESTAMP NULL
);

CREATE INDEX outbox_upublisert ON outbox (sekvensnummer) WHERE publisert_tidspunkt IS NULL;

CREATE TABLE migreringslogg
(
    løpenummer         BIGSERIAL PRIMARY KEY,
    fødselsnummer      VARCHAR(11) NOT NULL,
    tidspunkt          TIMESTAMPTZ NOT NULL DEFAULT now(),
    antall_vurderinger INTEGER     NOT NULL CHECK (antall_vurderinger >= 0),
    antall_hoppet_over INTEGER     NOT NULL CHECK (antall_hoppet_over >= 0 AND antall_hoppet_over <= antall_vurderinger)
);

CREATE INDEX idx_migreringslogg_fodselsnummer
    ON migreringslogg (fødselsnummer);

-- Rollen opprettes av Nais i dev, men finnes ikke i prod eller lokale databaser.
DO
$$
    BEGIN
        IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'sp-medlemskap-opprydding-dev')
        THEN
            GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "sp-medlemskap-opprydding-dev";
            GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "sp-medlemskap-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-medlemskap" IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO "sp-medlemskap-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-medlemskap" IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO "sp-medlemskap-opprydding-dev";
        END IF;
    END
$$;
