SELECT array_agg(n.nspname||'.'||c.relname||':'||c.relkind::text ORDER BY n.nspname,c.relname) = ARRAY[
    'dev_logs.capacity:r','dev_logs.events:r','dev_logs.events_sequence_seq:S',
    'dev_logs.manifests:r','dev_logs.producers:r','dev_logs.schema_migrations:r','dev_logs.scopes:r']
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%'
  AND c.relkind IN ('r','p','v','m','S','f');
