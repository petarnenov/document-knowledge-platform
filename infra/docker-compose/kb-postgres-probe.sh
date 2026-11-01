#!/bin/sh
set -eu
export PGPASSWORD="$POSTGRES_PASSWORD"
pg_isready --host=127.0.0.1 --username="$POSTGRES_USER" --dbname=knowledge_base >/dev/null 2>&1
psql --no-psqlrc --host=127.0.0.1 --username="$POSTGRES_USER" --dbname=knowledge_base --tuples-only --no-align \
 --command="SELECT 1 WHERE current_setting('fsync')='on' AND current_setting('synchronous_commit')='on' AND current_setting('full_page_writes')='on' AND EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='kb') AND has_database_privilege('dkp_kb_runtime','knowledge_base','CONNECT') AND NOT has_database_privilege('dkp_kb_runtime','knowledge_base','CREATE') AND NOT has_database_privilege('dkp_kb_runtime','knowledge_base','TEMP')" 2>/dev/null | grep -qx 1
