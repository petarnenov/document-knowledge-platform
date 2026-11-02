-- Owner-only provisioning, after immutable V1 and V2; never executed by the application.
CREATE ROLE dkp_log_writer LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS PASSWORD '__WRITER_PASSWORD__';
GRANT CONNECT ON DATABASE dkp_dev_logs TO dkp_log_writer;
GRANT USAGE ON SCHEMA dev_logs TO dkp_log_writer;
GRANT SELECT ON dev_logs.capacity,dev_logs.manifests,dev_logs.producers,dev_logs.scopes,dev_logs.events TO dkp_log_writer;
GRANT INSERT ON dev_logs.producers,dev_logs.scopes,dev_logs.events TO dkp_log_writer;
GRANT UPDATE(reserved_bytes) ON dev_logs.capacity,dev_logs.scopes TO dkp_log_writer;
GRANT UPDATE(revoked) ON dev_logs.producers TO dkp_log_writer;
GRANT USAGE ON SEQUENCE dev_logs.events_sequence_seq TO dkp_log_writer;
