-- Eksisterende rader nummereres etter opprettet før kolonnen blir identity, så rekkefølgen bevares.
ALTER TABLE outbox ADD COLUMN sekvensnummer BIGINT;

UPDATE outbox o
SET sekvensnummer = n.nr
FROM (SELECT id, row_number() OVER (ORDER BY opprettet, id) AS nr FROM outbox) n
WHERE o.id = n.id;

ALTER TABLE outbox ALTER COLUMN sekvensnummer SET NOT NULL;
ALTER TABLE outbox ALTER COLUMN sekvensnummer ADD GENERATED ALWAYS AS IDENTITY;

-- Nye rader fortsetter etter høyeste eksisterende sekvensnummer.
SELECT setval(pg_get_serial_sequence('outbox', 'sekvensnummer'), COALESCE(MAX(sekvensnummer), 0) + 1, false)
FROM outbox;

DROP INDEX outbox_upublisert;
CREATE INDEX outbox_upublisert ON outbox (sekvensnummer) WHERE publisert_tidspunkt IS NULL;
