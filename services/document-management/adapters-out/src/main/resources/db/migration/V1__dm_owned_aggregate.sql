-- Run only as the isolated DM migration owner. Runtime role provisioning is a deployment boundary.
CREATE SCHEMA IF NOT EXISTS dm;
REVOKE ALL ON SCHEMA dm FROM PUBLIC;
-- Database bootstrap revokes PUBLIC schema/database rights as database owner.

CREATE TABLE dm.documents (
    tenant_id varchar(256) NOT NULL,
    document_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL,
    creator_issuer text NOT NULL,
    creator_subject text NOT NULL,
    title varchar(256) NOT NULL CHECK (length(title) > 0),
    description varchar(4096) NOT NULL,
    lifecycle varchar(32) NOT NULL CHECK (lifecycle = 'DRAFT'),
    classification varchar(64) NOT NULL CHECK (classification = 'development-restricted'),
    classification_revision bigint NOT NULL CHECK (classification_revision = 1),
    active_version_id uuid,
    PRIMARY KEY (tenant_id, document_id)
);
CREATE INDEX documents_page ON dm.documents (tenant_id, created_at, document_id);
CREATE TABLE dm.retention (
    tenant_id varchar(256) NOT NULL,
    document_id uuid NOT NULL,
    policy_id varchar(64) NOT NULL CHECK (policy_id = 'development-unresolved'),
    policy_revision bigint NOT NULL CHECK (policy_revision = 1),
    resolution varchar(32) NOT NULL CHECK (resolution = 'UNRESOLVED'),
    retain_until timestamptz CHECK (retain_until IS NULL),
    destructive_eligible boolean NOT NULL CHECK (NOT destructive_eligible),
    PRIMARY KEY (tenant_id, document_id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES dm.documents
);
CREATE TABLE dm.acl_rules (
    tenant_id varchar(256) NOT NULL,
    document_id uuid NOT NULL,
    principal_issuer text NOT NULL,
    principal_subject text NOT NULL,
    action varchar(64) NOT NULL,
    effect varchar(5) NOT NULL CHECK (effect IN ('ALLOW','DENY')),
    PRIMARY KEY (tenant_id, document_id, principal_issuer, principal_subject, action, effect),
    FOREIGN KEY (tenant_id, document_id) REFERENCES dm.documents
);
CREATE INDEX acl_visibility ON dm.acl_rules (tenant_id, principal_issuer, principal_subject, action, effect, document_id);
CREATE TABLE dm.legal_holds (
    tenant_id varchar(256) NOT NULL,
    document_id uuid NOT NULL,
    hold_id uuid NOT NULL,
    reason varchar(1024) NOT NULL CHECK (length(reason) > 0),
    placed_at timestamptz NOT NULL,
    actor_issuer text NOT NULL,
    actor_subject text NOT NULL,
    PRIMARY KEY (tenant_id, document_id, hold_id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES dm.documents
);
CREATE TABLE dm.versions (
    tenant_id varchar(256) NOT NULL,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    version_number bigint NOT NULL CHECK (version_number > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    mime_type text NOT NULL,
    artifact_reference text NOT NULL,
    producer text NOT NULL,
    creator_issuer text NOT NULL,
    creator_subject text NOT NULL,
    created_at timestamptz NOT NULL,
    classification varchar(64) NOT NULL,
    review_state varchar(32) NOT NULL CHECK (review_state = 'UNREVIEWED'),
    PRIMARY KEY (tenant_id, document_id, version_id),
    UNIQUE (tenant_id, document_id, version_number),
    FOREIGN KEY (tenant_id, document_id) REFERENCES dm.documents
);
ALTER TABLE dm.documents ADD CONSTRAINT active_version_same_document
    FOREIGN KEY (tenant_id, document_id, active_version_id)
    REFERENCES dm.versions (tenant_id, document_id, version_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE dm.command_outcomes (
    tenant_id varchar(256) NOT NULL,
    actor_issuer text NOT NULL,
    actor_subject text NOT NULL,
    operation varchar(64) NOT NULL,
    resource_scope text NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    body_hash char(64) NOT NULL,
    document_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    status integer NOT NULL CHECK (status IN (200,201)),
    PRIMARY KEY (tenant_id, actor_issuer, actor_subject, operation, resource_scope, idempotency_key),
    FOREIGN KEY (tenant_id, document_id) REFERENCES dm.documents
);
CREATE TABLE dm.audit_facts (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    tenant_id varchar(256) NOT NULL,
    document_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    operation varchar(64) NOT NULL,
    actor_issuer text NOT NULL,
    actor_subject text NOT NULL,
    occurred_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, document_id) REFERENCES dm.documents
);
REVOKE ALL ON ALL TABLES IN SCHEMA dm FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA dm FROM PUBLIC;
GRANT USAGE ON SCHEMA dm TO dkp_dm_runtime;
GRANT SELECT, INSERT, UPDATE ON dm.documents, dm.retention TO dkp_dm_runtime;
GRANT SELECT, INSERT, DELETE ON dm.acl_rules TO dkp_dm_runtime;
GRANT SELECT, INSERT ON dm.legal_holds, dm.versions, dm.command_outcomes, dm.audit_facts TO dkp_dm_runtime;
GRANT USAGE ON SEQUENCE dm.audit_facts_audit_id_seq TO dkp_dm_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA dm REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA dm REVOKE ALL ON SEQUENCES FROM PUBLIC;
