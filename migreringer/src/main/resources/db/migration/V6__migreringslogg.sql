CREATE TABLE migreringslogg
(
    løpenummer          BIGSERIAL PRIMARY KEY,
    fødselsnummer       VARCHAR(11) NOT NULL,
    tidspunkt           TIMESTAMPTZ NOT NULL DEFAULT now(),
    antall_vurderinger  INTEGER     NOT NULL CHECK (antall_vurderinger >= 0),
    antall_hoppet_over  INTEGER     NOT NULL CHECK (antall_hoppet_over >= 0 AND antall_hoppet_over <= antall_vurderinger)
);

CREATE INDEX idx_migreringslogg_fodselsnummer
    ON migreringslogg (fødselsnummer);
