-- Render only reviewed hex/opaque secret placeholders in the private provision operation.
CREATE TABLE dev_logs.schema_migrations(version integer PRIMARY KEY, migration_sha char(64) NOT NULL);
INSERT INTO dev_logs.schema_migrations VALUES (1,'__MIGRATION_SHA__');
REVOKE ALL ON DATABASE dkp_dev_logs FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE ROLE dkp_log_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '__READER_PASSWORD__';
GRANT CONNECT ON DATABASE dkp_dev_logs TO dkp_log_reader;
GRANT USAGE ON SCHEMA dev_logs TO dkp_log_reader;
GRANT SELECT ON dev_logs.events TO dkp_log_reader;
REVOKE ALL ON dev_logs.schema_migrations FROM PUBLIC;
