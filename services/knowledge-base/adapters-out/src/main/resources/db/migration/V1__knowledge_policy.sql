-- Executed only by the KB-owned migrator. Database/role provisioning is external.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM pg_namespace n,LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a WHERE n.nspname='public' AND a.grantee=0)
 OR EXISTS (SELECT 1 FROM pg_database d,LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a WHERE d.datname=current_database() AND a.grantee=0)
 THEN RAISE EXCEPTION 'Database ownership preconditions not established'; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='dkp_kb_runtime' AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit AND NOT rolbypassrls)
 OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dkp_kb_runtime'))
 THEN RAISE EXCEPTION 'Runtime role preconditions not established'; END IF;
END $$;
REVOKE ALL ON SCHEMA kb FROM PUBLIC;

CREATE TABLE kb.tenant_policy_fences (
 tenant_id varchar(256) PRIMARY KEY CHECK(length(tenant_id)>0),
 revision bigint NOT NULL CHECK(revision BETWEEN 0 AND 9007199254740991)
);
CREATE TABLE kb.knowledge_collections (
 tenant_id varchar(256) NOT NULL REFERENCES kb.tenant_policy_fences(tenant_id),
 collection_id uuid NOT NULL,
 aggregate_revision bigint NOT NULL CHECK(aggregate_revision BETWEEN 0 AND 9007199254740991),
 membership_revision bigint NOT NULL CHECK(membership_revision BETWEEN 0 AND 9007199254740991),
 audience_policy_revision bigint NOT NULL CHECK(audience_policy_revision BETWEEN 1 AND 9007199254740991),
 title varchar(256) NOT NULL CHECK(length(title)>0), description varchar(4096) NOT NULL,
 status varchar(8) NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
 classification varchar(64) NOT NULL CHECK(classification='development-restricted'),
 classification_policy_revision bigint NOT NULL CHECK(classification_policy_revision=1),
 creator_issuer varchar(2048) NOT NULL CHECK(length(creator_issuer)>0),
 creator_subject varchar(256) NOT NULL CHECK(length(creator_subject)>0), created_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,collection_id)
);
CREATE TABLE kb.collection_acl_rules (
 tenant_id varchar(256) NOT NULL, collection_id uuid NOT NULL,
 principal_issuer varchar(2048) NOT NULL CHECK(length(principal_issuer)>0),
 principal_subject varchar(256) NOT NULL CHECK(length(principal_subject)>0),
 action varchar(64) NOT NULL CHECK(action IN ('knowledge.collection.create','knowledge.collection.inspect','knowledge.collection.update','knowledge.collection.archive','knowledge.acl.restrict','knowledge.acl.delegate','knowledge.audience.manage','knowledge.membership.manage','knowledge.policy.evaluate')),
 effect varchar(5) NOT NULL CHECK(effect IN ('ALLOW','DENY')),
 PRIMARY KEY(tenant_id,collection_id,principal_issuer,principal_subject,action,effect),
 FOREIGN KEY(tenant_id,collection_id) REFERENCES kb.knowledge_collections
);
CREATE TABLE kb.audience_policies (
 tenant_id varchar(256) NOT NULL, collection_id uuid NOT NULL,
 policy_revision bigint NOT NULL CHECK(policy_revision BETWEEN 1 AND 9007199254740991),
 effective_at timestamptz NOT NULL,
 classification varchar(64) NOT NULL CHECK(classification='development-restricted'),
 classification_policy_revision bigint NOT NULL CHECK(classification_policy_revision=1),
 sealed boolean NOT NULL DEFAULT false,
 PRIMARY KEY(tenant_id,collection_id,policy_revision),
 FOREIGN KEY(tenant_id,collection_id) REFERENCES kb.knowledge_collections
);
CREATE TABLE kb.audience_rules (
 tenant_id varchar(256) NOT NULL, collection_id uuid NOT NULL, policy_revision bigint NOT NULL,
 rule_number integer NOT NULL CHECK(rule_number BETWEEN 0 AND 99),
 selector_kind varchar(14) NOT NULL CHECK(selector_kind IN ('PRINCIPAL','TENANT','FIRM','RESOURCE_SCOPE')),
 selector_issuer varchar(2048), selector_subject varchar(256), selector_id varchar(256),
 effect varchar(5) NOT NULL CHECK(effect IN ('ALLOW','DENY')),
 CHECK((selector_kind='PRINCIPAL' AND selector_issuer IS NOT NULL AND length(selector_issuer)>0 AND selector_subject IS NOT NULL AND length(selector_subject)>0 AND selector_id IS NULL)
    OR (selector_kind='TENANT' AND selector_issuer IS NULL AND selector_subject IS NULL AND selector_id IS NOT NULL AND length(selector_id)>0)
    OR (selector_kind IN ('FIRM','RESOURCE_SCOPE') AND selector_issuer IS NOT NULL AND length(selector_issuer)>0 AND selector_subject IS NULL AND selector_id IS NOT NULL AND length(selector_id)>0)),
 PRIMARY KEY(tenant_id,collection_id,policy_revision,rule_number),
 UNIQUE NULLS NOT DISTINCT(tenant_id,collection_id,policy_revision,selector_kind,selector_issuer,selector_subject,selector_id,effect),
 FOREIGN KEY(tenant_id,collection_id,policy_revision) REFERENCES kb.audience_policies
);
ALTER TABLE kb.knowledge_collections ADD CONSTRAINT current_audience_policy
 FOREIGN KEY(tenant_id,collection_id,audience_policy_revision) REFERENCES kb.audience_policies
 DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE kb.collection_memberships (
 tenant_id varchar(256) NOT NULL, membership_id uuid NOT NULL, collection_id uuid NOT NULL, document_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision BETWEEN 0 AND 9007199254740991),
 state varchar(8) NOT NULL CHECK(state IN ('ACTIVE','INACTIVE')),
 sharing_scope varchar(15) NOT NULL CHECK(sharing_scope='SELECTED_TENANT'),
 creator_issuer varchar(2048) NOT NULL CHECK(length(creator_issuer)>0), creator_subject varchar(256) NOT NULL CHECK(length(creator_subject)>0),
 created_at timestamptz NOT NULL, reason varchar(1024) NOT NULL CHECK(length(reason)>0),
 PRIMARY KEY(tenant_id,membership_id), UNIQUE(tenant_id,collection_id,document_id),
 FOREIGN KEY(tenant_id,collection_id) REFERENCES kb.knowledge_collections
);
CREATE INDEX collection_memberships_document ON kb.collection_memberships(tenant_id,document_id,collection_id);
CREATE TABLE kb.membership_history (
 tenant_id varchar(256) NOT NULL, membership_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision BETWEEN 0 AND 9007199254740991),
 state varchar(8) NOT NULL CHECK(state IN ('ACTIVE','INACTIVE')),
 actor_issuer varchar(2048) NOT NULL CHECK(length(actor_issuer)>0), actor_subject varchar(256) NOT NULL CHECK(length(actor_subject)>0),
 reason varchar(1024) NOT NULL CHECK(length(reason)>0), changed_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,membership_id,revision), FOREIGN KEY(tenant_id,membership_id) REFERENCES kb.collection_memberships
);
CREATE TABLE kb.policy_revisions (
 tenant_id varchar(256) NOT NULL, collection_id uuid NOT NULL,
 aggregate_revision bigint NOT NULL CHECK(aggregate_revision BETWEEN 0 AND 9007199254740991),
 membership_revision bigint NOT NULL CHECK(membership_revision BETWEEN 0 AND 9007199254740991),
 policy_revision bigint NOT NULL CHECK(policy_revision BETWEEN 1 AND 9007199254740991),
 change_digest char(64) NOT NULL CHECK(change_digest ~ '^[0-9a-f]{64}$'), changed_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,collection_id,aggregate_revision), FOREIGN KEY(tenant_id,collection_id) REFERENCES kb.knowledge_collections
);
CREATE TABLE kb.document_membership_scopes (
 tenant_id varchar(256) NOT NULL REFERENCES kb.tenant_policy_fences(tenant_id), document_id uuid NOT NULL,
 set_revision bigint NOT NULL CHECK(set_revision BETWEEN 0 AND 9007199254740991), PRIMARY KEY(tenant_id,document_id)
);
CREATE TABLE kb.policy_snapshots (
 token_hash char(64) PRIMARY KEY CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 tenant_id varchar(256) NOT NULL REFERENCES kb.tenant_policy_fences(tenant_id),
 actor_issuer varchar(2048) NOT NULL CHECK(length(actor_issuer)>0), actor_subject varchar(256) NOT NULL CHECK(length(actor_subject)>0), document_id uuid NOT NULL,
 document_revision bigint NOT NULL CHECK(document_revision BETWEEN 0 AND 9007199254740991),
 classification varchar(64) NOT NULL CHECK(classification='development-restricted'), classification_policy_revision bigint NOT NULL CHECK(classification_policy_revision=1),
 tenant_fence bigint NOT NULL CHECK(tenant_fence BETWEEN 0 AND 9007199254740991),
 set_revision bigint NOT NULL CHECK(set_revision BETWEEN 0 AND 9007199254740991),
 payload_version integer NOT NULL CHECK(payload_version=1), payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 1 AND 4096),
 decision varchar(12) NOT NULL CHECK(decision IN ('COMPATIBLE','INCOMPATIBLE')), created_at timestamptz NOT NULL
);
CREATE INDEX policy_snapshots_tenant ON kb.policy_snapshots(tenant_id);
CREATE TABLE kb.command_outcomes (
 tenant_id varchar(256) NOT NULL, actor_issuer varchar(2048) NOT NULL CHECK(length(actor_issuer)>0), actor_subject varchar(256) NOT NULL CHECK(length(actor_subject)>0),
 operation varchar(64) NOT NULL CHECK(length(operation)>0), resource_scope varchar(256) NOT NULL CHECK(length(resource_scope)>0), idempotency_key varchar(128) NOT NULL CHECK(length(idempotency_key)>0),
 body_hash char(64) NOT NULL CHECK(body_hash ~ '^[0-9a-f]{64}$'),
 status integer NOT NULL CHECK(status IN (200,201)), collection_id uuid NOT NULL, membership_id uuid,
 revision bigint NOT NULL CHECK(revision BETWEEN 0 AND 9007199254740991),
 active_member_guard boolean NOT NULL,
 PRIMARY KEY(tenant_id,actor_issuer,actor_subject,operation,resource_scope,idempotency_key),
 FOREIGN KEY(tenant_id,collection_id) REFERENCES kb.knowledge_collections,
 FOREIGN KEY(tenant_id,membership_id) REFERENCES kb.collection_memberships
);
CREATE TABLE kb.audit_facts (
 event_id uuid PRIMARY KEY, tenant_id varchar(256) NOT NULL, collection_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision BETWEEN 0 AND 9007199254740991),
 actor_issuer varchar(2048) NOT NULL CHECK(length(actor_issuer)>0), actor_subject varchar(256) NOT NULL CHECK(length(actor_subject)>0),
 operation varchar(64) NOT NULL CHECK(length(operation)>0), reason varchar(1024) NOT NULL CHECK(length(reason)>0), occurred_at timestamptz NOT NULL, correlation_id uuid NOT NULL,
 membership_revision bigint NOT NULL CHECK(membership_revision BETWEEN 0 AND 9007199254740991),
 audience_policy_revision bigint NOT NULL CHECK(audience_policy_revision BETWEEN 1 AND 9007199254740991),
 FOREIGN KEY(tenant_id,collection_id) REFERENCES kb.knowledge_collections
);
CREATE TABLE kb.outbox_events (
 event_id uuid PRIMARY KEY, tenant_id varchar(256) NOT NULL, collection_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision BETWEEN 0 AND 9007199254740991),
 membership_revision bigint NOT NULL CHECK(membership_revision BETWEEN 0 AND 9007199254740991),
 audience_policy_revision bigint NOT NULL CHECK(audience_policy_revision BETWEEN 1 AND 9007199254740991),
 event_type varchar(64) NOT NULL CHECK(length(event_type)>0), schema_version integer NOT NULL CHECK(schema_version=1), occurred_at timestamptz NOT NULL,
 actor_issuer varchar(2048) NOT NULL CHECK(length(actor_issuer)>0), actor_subject varchar(256) NOT NULL CHECK(length(actor_subject)>0),
 correlation_id uuid NOT NULL, causation_hash char(64) NOT NULL CHECK(causation_hash ~ '^[0-9a-f]{64}$'),
 payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 1 AND 16384),
 FOREIGN KEY(tenant_id,collection_id) REFERENCES kb.knowledge_collections
);

-- Mutable projections can advance, never rebind identities, erase history, or reset counters.
CREATE FUNCTION kb.guard_collection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.tenant_id,NEW.collection_id,NEW.creator_issuer,NEW.creator_subject,NEW.created_at,NEW.classification,NEW.classification_policy_revision)
 IS DISTINCT FROM (OLD.tenant_id,OLD.collection_id,OLD.creator_issuer,OLD.creator_subject,OLD.created_at,OLD.classification,OLD.classification_policy_revision)
 OR NEW.aggregate_revision<>OLD.aggregate_revision+1 OR NEW.membership_revision<OLD.membership_revision
 OR NEW.membership_revision>OLD.membership_revision+1 OR NEW.audience_policy_revision<OLD.audience_policy_revision
 OR NEW.audience_policy_revision>OLD.audience_policy_revision+1 THEN
  RAISE EXCEPTION 'Invalid collection projection' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_collection BEFORE UPDATE ON kb.knowledge_collections FOR EACH ROW EXECUTE FUNCTION kb.guard_collection();
CREATE FUNCTION kb.guard_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  PERFORM 1 FROM kb.tenant_policy_fences WHERE tenant_id=NEW.tenant_id FOR UPDATE;
  IF (SELECT count(*) FROM kb.collection_memberships WHERE tenant_id=NEW.tenant_id AND collection_id=NEW.collection_id)>=100
  OR (SELECT count(*) FROM kb.collection_memberships WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id)>=50 THEN
   RAISE EXCEPTION 'Membership limit' USING ERRCODE='23514';
  END IF;
 ELSE
  IF (NEW.tenant_id,NEW.membership_id,NEW.collection_id,NEW.document_id,NEW.creator_issuer,NEW.creator_subject,NEW.created_at,NEW.sharing_scope)
  IS DISTINCT FROM (OLD.tenant_id,OLD.membership_id,OLD.collection_id,OLD.document_id,OLD.creator_issuer,OLD.creator_subject,OLD.created_at,OLD.sharing_scope)
  OR NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'Invalid membership projection' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_membership BEFORE INSERT OR UPDATE ON kb.collection_memberships FOR EACH ROW EXECUTE FUNCTION kb.guard_membership();
CREATE FUNCTION kb.guard_counter() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='tenant_policy_fences' THEN
  IF NEW.tenant_id<>OLD.tenant_id OR NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'Invalid tenant fence' USING ERRCODE='23514'; END IF;
 ELSE
  IF (NEW.tenant_id,NEW.document_id) IS DISTINCT FROM (OLD.tenant_id,OLD.document_id) OR NEW.set_revision<>OLD.set_revision+1 THEN RAISE EXCEPTION 'Invalid document fence' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_tenant_counter BEFORE UPDATE ON kb.tenant_policy_fences FOR EACH ROW EXECUTE FUNCTION kb.guard_counter();
CREATE TRIGGER guard_document_counter BEFORE UPDATE ON kb.document_membership_scopes FOR EACH ROW EXECUTE FUNCTION kb.guard_counter();
CREATE FUNCTION kb.guard_snapshot_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM kb.tenant_policy_fences WHERE tenant_id=NEW.tenant_id FOR UPDATE;
 IF (SELECT count(*) FROM kb.policy_snapshots WHERE tenant_id=NEW.tenant_id)>=10000 THEN RAISE EXCEPTION 'Snapshot limit' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_snapshot_limit BEFORE INSERT ON kb.policy_snapshots FOR EACH ROW EXECUTE FUNCTION kb.guard_snapshot_limit();
CREATE FUNCTION kb.guard_acl_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM kb.tenant_policy_fences WHERE tenant_id=NEW.tenant_id FOR UPDATE;
 IF (SELECT count(*) FROM kb.collection_acl_rules WHERE tenant_id=NEW.tenant_id AND collection_id=NEW.collection_id)>=100 THEN RAISE EXCEPTION 'ACL limit' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_acl_limit BEFORE INSERT ON kb.collection_acl_rules FOR EACH ROW EXECUTE FUNCTION kb.guard_acl_limit();
-- Every new policy is sealed before COMMIT. Only this exact-parent trigger may
-- change the seal; the runtime has no UPDATE permission on immutable policies.
CREATE FUNCTION kb.seal_audience_policy() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,kb AS $$
BEGIN
 UPDATE kb.audience_policies SET sealed=true WHERE tenant_id=NEW.tenant_id AND collection_id=NEW.collection_id AND policy_revision=NEW.policy_revision AND NOT sealed;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER seal_audience_policy AFTER INSERT ON kb.audience_policies DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION kb.seal_audience_policy();
-- Child inserts may occur during initial atomic policy construction only.
-- This durable seal does not depend on time, xmin/XID reuse or an application flag.
CREATE FUNCTION kb.guard_audience_rule_creation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM kb.audience_policies p WHERE p.tenant_id=NEW.tenant_id AND p.collection_id=NEW.collection_id AND p.policy_revision=NEW.policy_revision AND NOT p.sealed)
 THEN RAISE EXCEPTION 'Immutable audience policy is sealed' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_audience_rule_creation BEFORE INSERT ON kb.audience_rules FOR EACH ROW EXECUTE FUNCTION kb.guard_audience_rule_creation();
REVOKE ALL ON FUNCTION kb.guard_collection(),kb.guard_membership(),kb.guard_counter(),kb.guard_snapshot_limit(),kb.guard_acl_limit(),kb.seal_audience_policy(),kb.guard_audience_rule_creation() FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA kb FROM PUBLIC;
GRANT USAGE ON SCHEMA kb TO dkp_kb_runtime;
GRANT SELECT,INSERT,UPDATE ON kb.tenant_policy_fences,kb.knowledge_collections,kb.collection_memberships,kb.document_membership_scopes TO dkp_kb_runtime;
GRANT SELECT,INSERT,DELETE ON kb.collection_acl_rules TO dkp_kb_runtime;
GRANT SELECT,INSERT ON kb.audience_policies,kb.audience_rules,kb.membership_history,kb.policy_revisions,kb.policy_snapshots,kb.command_outcomes,kb.audit_facts,kb.outbox_events TO dkp_kb_runtime;
