CREATE TABLE medlemskapsproving
(
    løpenummer             BIGSERIAL PRIMARY KEY,
    id                     UUID        NOT NULL UNIQUE,
    fødselsnummer          VARCHAR(11) NOT NULL,
    skjæringstidspunkt     DATE        NOT NULL,
    startet                TIMESTAMPTZ NOT NULL,
    tilstand               TEXT        NOT NULL,
    utestående_behov       TEXT,
    medlemskapsvurdering_id UUID,
    opprettet              TIMESTAMPTZ NOT NULL DEFAULT now(),
    endret                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    kategori               TEXT        NOT NULL,

    CONSTRAINT medlemskapsproving_tilstand_er_konsistent CHECK (
        (tilstand = 'STARTET' AND utestående_behov IS NULL AND medlemskapsvurdering_id IS NULL) OR
        (tilstand = 'VENTER_PÅ_GRUNNLAG' AND utestående_behov IS NOT NULL AND medlemskapsvurdering_id IS NULL) OR
        (tilstand = 'FULLFØRT' AND utestående_behov IS NULL AND medlemskapsvurdering_id IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uix_medlemskapsproving_aktiv
    ON medlemskapsproving (fødselsnummer, skjæringstidspunkt, kategori)
    WHERE tilstand <> 'FULLFØRT';

CREATE INDEX idx_medlemskapsproving_noekkel
    ON medlemskapsproving (fødselsnummer, skjæringstidspunkt, løpenummer DESC);

CREATE TABLE vilkarsvurdering
(
    id                UUID        NOT NULL UNIQUE,
    vilkårskode       TEXT        NOT NULL,
    utfall            TEXT        NOT NULL,
    vurdert_tidspunkt TIMESTAMPTZ,
    kilde             JSONB       NOT NULL,
    opprettet         TIMESTAMPTZ NOT NULL DEFAULT now(),
    lovreferanse      JSONB       NOT NULL,
    vurderingskilde   TEXT        NOT NULL
);

CREATE TABLE medlemskapsvurdering
(
    id                          UUID        NOT NULL UNIQUE,
    fødselsnummer               VARCHAR(11) NOT NULL,
    skjæringstidspunkt          DATE        NOT NULL,
    vurderingskilde             TEXT        NOT NULL,
    opprettet                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    medlemskap_ok               BOOLEAN     NOT NULL DEFAULT false,
    vurdert_tidspunkt           TIMESTAMPTZ NOT NULL,
    kategori                    TEXT        NOT NULL,
    avgjørende_vilkårsvurdering UUID,

    CONSTRAINT fk_medlemskapsvurdering_avgjorende_vilkarsvurdering
        FOREIGN KEY (avgjørende_vilkårsvurdering) REFERENCES vilkarsvurdering (id)
);

CREATE INDEX idx_medlemskapsvurdering_noekkel
    ON medlemskapsvurdering (fødselsnummer, skjæringstidspunkt, vurdert_tidspunkt DESC, opprettet DESC);

CREATE TABLE medlemskapsvurdering_vilkarsvurdering
(
    medlemskapsvurdering_id UUID NOT NULL REFERENCES medlemskapsvurdering (id),
    vilkarsvurdering_id     UUID NOT NULL REFERENCES vilkarsvurdering (id),
    UNIQUE (medlemskapsvurdering_id, vilkarsvurdering_id),
    opprettet              TIMESTAMP DEFAULT now()
);
