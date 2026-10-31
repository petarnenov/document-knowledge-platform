-- Synthetic local database bootstrap only. Secret values come from the local
-- ignored environment, never SQL source, command arguments or echoed commands.
\set ON_ERROR_STOP on
\getenv migrator_password DKP_DM_MIGRATOR_PASSWORD
\getenv runtime_password DKP_DM_RUNTIME_PASSWORD
CREATE ROLE dkp_dm_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'migrator_password';
CREATE ROLE dkp_dm_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';
CREATE DATABASE dkp_dm OWNER dkp_local_db_admin;
REVOKE ALL ON DATABASE dkp_dm FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres, template1 FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE dkp_dm TO dkp_dm_migrator;
GRANT CONNECT ON DATABASE dkp_dm TO dkp_dm_runtime;
\connect dkp_dm
REVOKE ALL ON SCHEMA public FROM PUBLIC, dkp_dm_migrator, dkp_dm_runtime;
CREATE SCHEMA dm AUTHORIZATION dkp_dm_migrator;
REVOKE ALL ON SCHEMA dm FROM PUBLIC;
ALTER ROLE dkp_dm_migrator IN DATABASE dkp_dm SET search_path = dm;
ALTER ROLE dkp_dm_runtime IN DATABASE dkp_dm SET search_path = dm;
