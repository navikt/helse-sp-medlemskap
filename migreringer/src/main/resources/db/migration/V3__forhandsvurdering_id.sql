-- Hver forhåndsvurdering får en id, så en medlemskapsvurdering kan peke på nøyaktig den meldingen
-- den bygger på. Det kan komme flere forhåndsvurderinger for samme søknad. Rader som finnes fra før,
-- får en tilfeldig id. Nye rader får id fra applikasjonen.
ALTER TABLE forhandsvurdering
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE forhandsvurdering
    ALTER COLUMN id DROP DEFAULT;

ALTER TABLE forhandsvurdering
    ADD CONSTRAINT forhandsvurdering_id_unik UNIQUE (id);
