SELECT version::text||':'||migration_sha FROM dev_logs.schema_migrations ORDER BY version;
