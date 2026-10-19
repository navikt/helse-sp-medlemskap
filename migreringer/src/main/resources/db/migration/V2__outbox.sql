CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    melding JSONB NOT NULL,
    fodselsnummer TEXT NOT NULL,
    opprettet TIMESTAMP NOT NULL DEFAULT now(),
    publisert_tidspunkt TIMESTAMP NULL
);

CREATE INDEX outbox_upublisert ON outbox (opprettet) WHERE publisert_tidspunkt IS NULL;
