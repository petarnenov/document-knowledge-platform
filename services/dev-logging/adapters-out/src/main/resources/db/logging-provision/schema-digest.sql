-- Capture metadata, not event data/secrets. Retained privately at first owned migration;
-- later starts/status/updates compare it before accepting schema drift.
WITH entries(label, body) AS (
    SELECT 'database:'||d.datname, jsonb_build_array(pg_get_userbyid(d.datdba),d.datacl::text)
    FROM pg_database d WHERE d.datname IN ('dkp_dev_logs','postgres','template1')
    UNION ALL
    SELECT 'role:dkp_log_reader',jsonb_build_array(r.rolsuper,r.rolcreatedb,r.rolcreaterole,r.rolinherit,r.rolreplication,r.rolbypassrls,r.rolcanlogin,r.rolvaliduntil)
    FROM pg_roles r WHERE r.rolname='dkp_log_reader'
    UNION ALL
    SELECT 'membership:'||parent.rolname,to_jsonb(a.admin_option)
    FROM pg_auth_members a JOIN pg_roles member ON member.oid=a.member JOIN pg_roles parent ON parent.oid=a.roleid WHERE member.rolname='dkp_log_reader'
    UNION ALL
    SELECT 'schema:'||n.nspname, jsonb_build_array(pg_get_userbyid(n.nspowner),n.nspacl::text)
    FROM pg_namespace n WHERE n.nspname IN ('dev_logs','public')
    UNION ALL
    SELECT 'relation:'||c.relname, jsonb_build_array(c.relkind,c.relpersistence,c.relrowsecurity,c.relforcerowsecurity,pg_get_userbyid(c.relowner),c.relacl::text)
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='dev_logs'
    UNION ALL
    SELECT 'column:'||c.relname||':'||a.attnum, jsonb_build_array(a.attname,a.attacl::text,format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,pg_get_expr(d.adbin,d.adrelid))
    FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
    LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
    WHERE n.nspname='dev_logs' AND a.attnum>0 AND NOT a.attisdropped
    UNION ALL
    SELECT 'constraint:'||c.relname||':'||k.conname,to_jsonb(pg_get_constraintdef(k.oid,true))
    FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='dev_logs'
    UNION ALL
    SELECT 'index:'||c.relname,to_jsonb(pg_get_indexdef(c.oid))
    FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='dev_logs'
    UNION ALL
    SELECT 'policy:'||c.relname||':'||p.polname,to_jsonb(p.*)
    FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='dev_logs'
    UNION ALL
    SELECT 'trigger:'||c.relname||':'||t.tgname,to_jsonb(pg_get_triggerdef(t.oid,true))
    FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='dev_logs' AND NOT t.tgisinternal
)
SELECT encode(sha256(convert_to(jsonb_agg(jsonb_build_array(label,body) ORDER BY label COLLATE "C")::text,'UTF8')),'hex') FROM entries;
