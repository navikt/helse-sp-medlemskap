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
