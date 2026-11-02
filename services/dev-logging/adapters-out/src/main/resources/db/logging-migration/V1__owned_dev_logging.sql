-- Dedicated logging database only. Run as its migration owner, never a domain DB role.
CREATE SCHEMA dev_logs;
REVOKE ALL ON SCHEMA dev_logs FROM PUBLIC;

CREATE TABLE dev_logs.capacity (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    reserved_bytes bigint NOT NULL CHECK (reserved_bytes BETWEEN 0 AND 1073741824)
);
INSERT INTO dev_logs.capacity(singleton,reserved_bytes) VALUES (true,0);

CREATE TABLE dev_logs.manifests (
    manifest_sha varchar(64) PRIMARY KEY CHECK (manifest_sha ~ '^[0-9a-f]{64}$'),
    build_id uuid NOT NULL CHECK (build_id <> '00000000-0000-0000-0000-000000000000'),
    component varchar(96) NOT NULL CHECK (component ~ '^[a-z][a-z0-9.-]*$'),
    git_sha varchar(40) NOT NULL CHECK (git_sha ~ '^[0-9a-f]{40}$'),
    source_fingerprint varchar(64) NOT NULL CHECK (source_fingerprint ~ '^[0-9a-f]{64}$'),
    vocabulary_sha varchar(64) NOT NULL CHECK (vocabulary_sha ~ '^[0-9a-f]{64}$'),
    manifest_bytes bytea NOT NULL CHECK (octet_length(manifest_bytes) BETWEEN 1 AND 1048576),
    reserved_bytes bigint NOT NULL CHECK (reserved_bytes >= 1024 + octet_length(manifest_bytes)),
    installed_at timestamptz NOT NULL
);

CREATE TABLE dev_logs.producers (
    producer_id uuid PRIMARY KEY CHECK (producer_id <> '00000000-0000-0000-0000-000000000000'),
    manifest_sha varchar(64) NOT NULL REFERENCES dev_logs.manifests,
    run_id uuid NOT NULL CHECK (run_id <> '00000000-0000-0000-0000-000000000000'),
    created_at timestamptz NOT NULL,
    accept_until timestamptz NOT NULL CHECK (accept_until > created_at AND accept_until <= created_at + interval '48 hours'),
    revoked boolean NOT NULL DEFAULT false,
    reserved_bytes bigint NOT NULL CHECK (reserved_bytes >= 1024)
);

CREATE TABLE dev_logs.scopes (
    scope_id uuid PRIMARY KEY CHECK (scope_id <> '00000000-0000-0000-0000-000000000000'),
    producer_id uuid NOT NULL REFERENCES dev_logs.producers,
    target_manifest_sha varchar(64) NOT NULL REFERENCES dev_logs.manifests,
    target_run_id uuid NOT NULL CHECK (target_run_id <> '00000000-0000-0000-0000-000000000000'),
    context_epoch uuid NOT NULL CHECK (context_epoch <> '00000000-0000-0000-0000-000000000000'),
    kind varchar(32) NOT NULL CHECK (kind IN ('TENANT','AUTHENTICATED_UNSELECTED','UNATTRIBUTED','OPERATOR_ONLY')),
    issuer varchar(512),
    subject varchar(256),
    tenant_id varchar(256),
    created_at timestamptz NOT NULL,
    capture_until timestamptz NOT NULL CHECK (capture_until > created_at AND capture_until <= created_at + interval '5 minutes'),
    replay_until timestamptz NOT NULL CHECK (replay_until >= capture_until AND replay_until <= created_at + interval '48 hours'),
    reserved_bytes bigint NOT NULL CHECK (reserved_bytes >= 1024),
    UNIQUE (producer_id,scope_id),
    CHECK (
        (kind='TENANT' AND issuer IS NOT NULL AND length(issuer)>0 AND subject IS NOT NULL AND length(subject)>0 AND tenant_id IS NOT NULL AND length(tenant_id)>0)
        OR (kind='AUTHENTICATED_UNSELECTED' AND issuer IS NOT NULL AND length(issuer)>0 AND subject IS NOT NULL AND length(subject)>0 AND tenant_id IS NULL)
        OR (kind IN ('UNATTRIBUTED','OPERATOR_ONLY') AND issuer IS NULL AND subject IS NULL AND tenant_id IS NULL)
    )
);
CREATE INDEX scope_replay_horizon ON dev_logs.scopes(replay_until,scope_id);

-- No FK to ephemeral producer/scope rows: seven-day evidence survives their safe reclamation.
CREATE TABLE dev_logs.events (
    producer_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    event_id uuid NOT NULL,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    build_id uuid NOT NULL,
    run_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    component varchar(96) NOT NULL,
    git_sha varchar(40) NOT NULL,
    source_fingerprint varchar(64) NOT NULL,
    event_time timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    severity varchar(8) NOT NULL CHECK (severity IN ('TRACE','DEBUG','INFO','WARN','ERROR','FATAL')),
    event_name varchar(128) NOT NULL,
    correlation_id uuid NOT NULL,
    trace_id varchar(32),
    scope_kind varchar(32) NOT NULL CHECK (scope_kind IN ('TENANT','AUTHENTICATED_UNSELECTED','UNATTRIBUTED','OPERATOR_ONLY')),
    actor_issuer varchar(512),
    actor_subject varchar(256),
    tenant_id varchar(256),
    canonical_hash varchar(64) NOT NULL CHECK (canonical_hash ~ '^[0-9a-f]{64}$'),
    canonical_payload bytea CHECK (canonical_payload IS NULL OR octet_length(canonical_payload) BETWEEN 1 AND 8192),
    retain_until timestamptz NOT NULL,
    reserved_bytes bigint NOT NULL CHECK (reserved_bytes >= 1024 + coalesce(octet_length(canonical_payload),0)),
    PRIMARY KEY (producer_id,scope_id,event_id),
    CHECK (retain_until >= received_at + interval '7 days'),
    CHECK (
        (scope_kind='TENANT' AND actor_issuer IS NOT NULL AND actor_subject IS NOT NULL AND tenant_id IS NOT NULL)
        OR (scope_kind='AUTHENTICATED_UNSELECTED' AND actor_issuer IS NOT NULL AND actor_subject IS NOT NULL AND tenant_id IS NULL)
        OR (scope_kind IN ('UNATTRIBUTED','OPERATOR_ONLY') AND actor_issuer IS NULL AND actor_subject IS NULL AND tenant_id IS NULL)
    )
);
CREATE INDEX log_build_page ON dev_logs.events(build_id,received_at,sequence);
CREATE INDEX log_run_page ON dev_logs.events(run_id,received_at,sequence);
CREATE INDEX log_component_page ON dev_logs.events(component,received_at,sequence);
CREATE INDEX log_correlation_page ON dev_logs.events(correlation_id,received_at,sequence);
CREATE INDEX log_trace_page ON dev_logs.events(trace_id,received_at,sequence) WHERE trace_id IS NOT NULL;
CREATE INDEX log_tenant_page ON dev_logs.events(tenant_id,received_at,sequence) WHERE scope_kind='TENANT';
CREATE INDEX log_retention ON dev_logs.events(retain_until,sequence);

-- Initial privileges are deny-by-default. Runtime/bootstrap provisioning grants only owned DML.
REVOKE ALL ON ALL TABLES IN SCHEMA dev_logs FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA dev_logs FROM PUBLIC;
