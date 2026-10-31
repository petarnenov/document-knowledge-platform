#!/bin/sh
set -eu
# Local health only; password never enters arguments/output.
export PGPASSWORD="$POSTGRES_PASSWORD"
pg_isready --host=127.0.0.1 --username="$POSTGRES_USER" --dbname=dkp_dm >/dev/null 2>&1
psql --no-psqlrc --host=127.0.0.1 --username="$POSTGRES_USER" --dbname=dkp_dm --tuples-only --no-align \
    --command="SELECT 1 FROM pg_namespace WHERE nspname='dm' AND has_database_privilege('dkp_dm_runtime','dkp_dm','CONNECT')" 2>/dev/null | grep -qx 1
