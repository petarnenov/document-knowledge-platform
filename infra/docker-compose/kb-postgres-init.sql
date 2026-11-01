-- Synthetic KB-only provisioning. Passwords arrive through private process env.
\set ON_ERROR_STOP on
\getenv migrator_password DKP_KB_MIGRATOR_PASSWORD
\getenv runtime_password DKP_KB_RUNTIME_PASSWORD
CREATE ROLE dkp_kb_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'migrator_password';
CREATE ROLE dkp_kb_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';
CREATE DATABASE knowledge_base OWNER dkp_kb_local_admin;
REVOKE ALL ON DATABASE knowledge_base FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres, template1 FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE knowledge_base TO dkp_kb_migrator;
GRANT CONNECT ON DATABASE knowledge_base TO dkp_kb_runtime;
\connect knowledge_base
REVOKE ALL ON SCHEMA public FROM PUBLIC, dkp_kb_migrator, dkp_kb_runtime;
CREATE SCHEMA kb AUTHORIZATION dkp_kb_migrator;
REVOKE ALL ON SCHEMA kb FROM PUBLIC;
ALTER ROLE dkp_kb_migrator IN DATABASE knowledge_base SET search_path = kb;
ALTER ROLE dkp_kb_runtime IN DATABASE knowledge_base SET search_path = kb;
