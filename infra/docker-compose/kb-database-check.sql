-- Post-migration operational verification only. Returns one safe boolean result.
-- Execute as the isolated provisioning admin in knowledge_base; never as runtime.
WITH expected(name) AS (VALUES
 ('tenant_policy_fences'), ('knowledge_collections'), ('collection_acl_rules'),
 ('audience_policies'), ('audience_rules'), ('collection_memberships'),
 ('membership_history'), ('policy_revisions'), ('document_membership_scopes'),
 ('policy_snapshots'), ('command_outcomes'), ('audit_facts'), ('outbox_events'))
SELECT 1 WHERE current_database()='knowledge_base'
 AND current_setting('fsync')='on'
 AND current_setting('synchronous_commit')='on'
 AND current_setting('full_page_writes')='on'
 AND (SELECT count(*) FROM pg_tables WHERE schemaname='kb' AND tablename<>'flyway_schema_history')=13
 AND NOT EXISTS(SELECT 1 FROM expected WHERE to_regclass('kb.'||name) IS NULL)
 AND NOT has_schema_privilege('dkp_kb_runtime','kb','CREATE')
 AND NOT has_schema_privilege('dkp_kb_runtime','public','CREATE')
 AND NOT has_database_privilege('dkp_kb_runtime','knowledge_base','CREATE')
 AND NOT has_database_privilege('dkp_kb_runtime','knowledge_base','TEMP')
 AND NOT has_database_privilege('dkp_kb_runtime','postgres','CONNECT')
 AND NOT has_database_privilege('dkp_kb_runtime','template1','CONNECT')
 AND NOT has_table_privilege('dkp_kb_runtime','kb.flyway_schema_history','SELECT')
 AND NOT has_table_privilege('dkp_kb_runtime','kb.audit_facts','UPDATE')
 AND NOT has_table_privilege('dkp_kb_runtime','kb.command_outcomes','DELETE')
 AND NOT has_table_privilege('dkp_kb_runtime','kb.audience_policies','UPDATE')
 AND NOT has_table_privilege('dkp_kb_runtime','kb.audience_rules','DELETE')
 AND (SELECT count(*) FROM pg_roles WHERE rolname IN ('dkp_kb_runtime','dkp_kb_migrator')
      AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
      AND NOT rolinherit AND NOT rolreplication AND NOT rolbypassrls)=2
 AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member IN
     (SELECT oid FROM pg_roles WHERE rolname IN ('dkp_kb_runtime','dkp_kb_migrator')));
