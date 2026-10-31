-- DM ownership remains unchanged. The migration owner, never the runtime role, runs DDL.
CREATE TABLE dm.upload_global_resource_account (
    account_key smallint PRIMARY KEY CHECK (account_key = 1),
    admitted_count integer NOT NULL CHECK (admitted_count BETWEEN 0 AND 10),
    reserved_transient_bytes bigint NOT NULL CHECK (reserved_transient_bytes BETWEEN 0 AND 2000000000),
    promised_original_bytes bigint NOT NULL CHECK (promised_original_bytes >= 0),
    retained_original_bytes bigint NOT NULL CHECK (retained_original_bytes >= 0),
    revision bigint NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    CHECK (promised_original_bytes + retained_original_bytes <= 100000000000)
);
INSERT INTO dm.upload_global_resource_account VALUES (1,0,0,0,0,0);
CREATE TABLE dm.upload_resource_accounts (
    tenant_id varchar(256) PRIMARY KEY,
    admitted_count integer NOT NULL CHECK (admitted_count BETWEEN 0 AND 5),
    reserved_transient_bytes bigint NOT NULL CHECK (reserved_transient_bytes BETWEEN 0 AND 1000000000),
    promised_original_bytes bigint NOT NULL CHECK (promised_original_bytes >= 0),
    retained_original_bytes bigint NOT NULL CHECK (retained_original_bytes >= 0),
    revision bigint NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    CHECK (promised_original_bytes + retained_original_bytes <= 5000000000)
);
CREATE TABLE dm.upload_slots (
    tenant_id varchar(256) NOT NULL,
    upload_id uuid NOT NULL,
    document_id uuid NOT NULL,
    actor_issuer varchar(2048) NOT NULL,
    actor_subject varchar(256) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    expected_document_revision bigint NOT NULL CHECK (expected_document_revision BETWEEN 0 AND 9007199254740991),
    declared_format varchar(4) NOT NULL CHECK (declared_format IN ('PDF','DOCX','PPTX','XLSX')),
    declared_size bigint NOT NULL CHECK (declared_size BETWEEN 1 AND 100000000),
    state varchar(16) NOT NULL CHECK (state IN ('INITIALIZED','RECEIVING','QUARANTINED','VERIFYING','VERIFIED','CONSUMED','REJECTED','EXPIRED')),
    state_revision bigint NOT NULL CHECK (state_revision BETWEEN 0 AND 9007199254740991),
    body_idempotency_key varchar(128),
    body_sha256 char(64) CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    actual_size bigint CHECK (actual_size = declared_size),
    quarantine_bucket varchar(63), quarantine_key varchar(138), quarantine_version_id varchar(1024),
    attempt_count smallint NOT NULL CHECK (attempt_count BETWEEN 0 AND 3),
    transfer_attempt_count smallint NOT NULL CHECK (transfer_attempt_count BETWEEN 0 AND 3),
    lease_id uuid, lease_until timestamptz,
    possible_original_key varchar(138), possible_original_version_id varchar(1024),
    copy_write_settled boolean NOT NULL,
    copy_cleanup_confirmed boolean NOT NULL,
    quarantine_cleanup_confirmed boolean NOT NULL DEFAULT false,
    transient_reservation bigint NOT NULL CHECK (transient_reservation BETWEEN 0 AND 2 * declared_size),
    failure_code varchar(32), cleanup_pending boolean NOT NULL, quota_held boolean NOT NULL,
    PRIMARY KEY (tenant_id,upload_id),
    UNIQUE (tenant_id,document_id,upload_id),
    UNIQUE (tenant_id,document_id,upload_id,actor_issuer,actor_subject),
    UNIQUE (tenant_id,upload_id,actor_issuer,actor_subject),
    FOREIGN KEY (tenant_id,document_id) REFERENCES dm.documents,
    FOREIGN KEY (tenant_id) REFERENCES dm.upload_resource_accounts,
    CHECK (expires_at = created_at + interval '60 minutes'),
    CHECK ((state IN ('RECEIVING','VERIFYING')) = (lease_id IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (lease_until IS NULL OR (lease_until <= expires_at AND lease_until > created_at)),
    CHECK (quarantine_bucket IS NULL OR quarantine_bucket = 'dkp-quarantine'),
    CHECK (quarantine_key IS NULL OR (quarantine_key ~ '^[0-9a-f]{64}/[0-9a-f-]{36}/[0-9a-f-]{36}$' AND split_part(quarantine_key,'/',1)=encode(sha256(convert_to(tenant_id,'UTF8')),'hex') AND split_part(quarantine_key,'/',2)=document_id::text)),
    CHECK (possible_original_key IS NULL OR (possible_original_key ~ '^[0-9a-f]{64}/[0-9a-f-]{36}/[0-9a-f-]{36}$' AND split_part(possible_original_key,'/',1)=encode(sha256(convert_to(tenant_id,'UTF8')),'hex') AND split_part(possible_original_key,'/',2)=document_id::text)),
    CHECK (quarantine_version_id IS NULL OR (length(quarantine_version_id)>0 AND octet_length(quarantine_version_id)<=1024 AND quarantine_version_id <> 'null')),
    CHECK (possible_original_version_id IS NULL OR (length(possible_original_version_id)>0 AND octet_length(possible_original_version_id)<=1024 AND possible_original_version_id <> 'null')),
    CHECK (failure_code IS NULL OR failure_code IN ('INVALID_FILE','MALWARE_DETECTED','STRUCTURE_LIMIT','UNSUPPORTED_CONTENT','DEPENDENCY_UNAVAILABLE','UPLOAD_EXPIRED','TRANSFER_INCOMPLETE'))
);
CREATE INDEX upload_slots_recovery ON dm.upload_slots (state,expires_at,tenant_id,upload_id);
CREATE TABLE dm.upload_verification_facts (
    tenant_id varchar(256) NOT NULL, evidence_id uuid NOT NULL,
    document_id uuid NOT NULL, upload_id uuid NOT NULL,
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    source_size bigint NOT NULL CHECK (source_size BETWEEN 1 AND 100000000),
    source_bucket varchar(63) NOT NULL CHECK (source_bucket = 'dkp-quarantine'),
    source_key varchar(138) NOT NULL, source_version_id varchar(1024) NOT NULL CHECK (length(source_version_id)>0 AND octet_length(source_version_id)<=1024 AND source_version_id <> 'null'),
    detected_format varchar(4) NOT NULL CHECK (detected_format IN ('PDF','DOCX','PPTX','XLSX')),
    detected_mime varchar(128) NOT NULL,
    inspector_plugin varchar(64) NOT NULL CHECK (inspector_plugin = 'dkp-structure-inspector'),
    inspector_version varchar(32) NOT NULL CHECK (inspector_version = '1'),
    inspector_image_digest varchar(71) NOT NULL CHECK (inspector_image_digest ~ '^sha256:[0-9a-f]{64}$'),
    profile_digest char(64) NOT NULL CHECK (profile_digest ~ '^[0-9a-f]{64}$'),
    inspected_at timestamptz NOT NULL,
    page_count integer CHECK (page_count BETWEEN 1 AND 1000),
    scanner_engine_version varchar(32) NOT NULL CHECK (scanner_engine_version = '1.5.4'),
    standard_database_version varchar(256) NOT NULL,
    standard_database_updated_at timestamptz NOT NULL,
    standard_database_digest char(64) NOT NULL CHECK (standard_database_digest ~ '^[0-9a-f]{64}$'),
    scanned_at timestamptz NOT NULL,
    verified_at timestamptz NOT NULL,
    result varchar(8) NOT NULL CHECK (result = 'VERIFIED'),
    PRIMARY KEY (tenant_id,evidence_id),
    UNIQUE (tenant_id,document_id,upload_id,evidence_id),
    FOREIGN KEY (tenant_id,document_id,upload_id) REFERENCES dm.upload_slots (tenant_id,document_id,upload_id),
    CHECK (standard_database_updated_at <= scanned_at AND scanned_at-standard_database_updated_at <= interval '24 hours'),
    CHECK (verified_at >= scanned_at AND verified_at >= inspected_at AND verified_at-standard_database_updated_at <= interval '24 hours'),
    CHECK ((detected_format='PDF') = (page_count IS NOT NULL)),
    CHECK ((detected_format,detected_mime) IN (
      ('PDF','application/pdf'),
      ('DOCX','application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
      ('PPTX','application/vnd.openxmlformats-officedocument.presentationml.presentation'),
      ('XLSX','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')))
);
CREATE TABLE dm.upload_receipts (
    tenant_id varchar(256) NOT NULL, receipt_id uuid NOT NULL, upload_id uuid NOT NULL,
    document_id uuid NOT NULL, actor_issuer varchar(2048) NOT NULL, actor_subject varchar(256) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 100000000),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    detected_format varchar(4) NOT NULL, detected_mime varchar(128) NOT NULL,
    original_bucket varchar(63) NOT NULL CHECK (original_bucket = 'dkp-originals'),
    original_key varchar(138) NOT NULL,
    original_version_id varchar(1024) NOT NULL CHECK (length(original_version_id)>0 AND octet_length(original_version_id)<=1024 AND original_version_id <> 'null'),
    verification_evidence_id uuid NOT NULL,
    created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,receipt_id), UNIQUE (tenant_id,upload_id), UNIQUE (tenant_id,upload_id,receipt_id),
    UNIQUE (tenant_id,document_id,receipt_id,actor_issuer,actor_subject),
    FOREIGN KEY (tenant_id,document_id,upload_id,actor_issuer,actor_subject) REFERENCES dm.upload_slots (tenant_id,document_id,upload_id,actor_issuer,actor_subject),
    FOREIGN KEY (tenant_id,document_id,upload_id,verification_evidence_id) REFERENCES dm.upload_verification_facts (tenant_id,document_id,upload_id,evidence_id),
    CHECK (created_at <= expires_at),
    CHECK (original_key ~ '^[0-9a-f]{64}/[0-9a-f-]{36}/[0-9a-f-]{36}$' AND split_part(original_key,'/',1)=encode(sha256(convert_to(tenant_id,'UTF8')),'hex') AND split_part(original_key,'/',2)=document_id::text)
);
CREATE TABLE dm.upload_receipt_consumptions (
    tenant_id varchar(256) NOT NULL, receipt_id uuid NOT NULL, document_id uuid NOT NULL, version_id uuid NOT NULL,
    actor_issuer varchar(2048) NOT NULL, actor_subject varchar(256) NOT NULL,
    append_operation varchar(64) NOT NULL CHECK (append_operation='document.version.append'), append_resource_scope text NOT NULL CHECK (append_resource_scope=document_id::text),
    append_idempotency_key varchar(128) NOT NULL, append_fingerprint char(64) NOT NULL CHECK (append_fingerprint ~ '^[0-9a-f]{64}$'),
    consumed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,receipt_id), UNIQUE (tenant_id,document_id,version_id),
    FOREIGN KEY (tenant_id,document_id,receipt_id,actor_issuer,actor_subject) REFERENCES dm.upload_receipts (tenant_id,document_id,receipt_id,actor_issuer,actor_subject),
    FOREIGN KEY (tenant_id,document_id,version_id) REFERENCES dm.versions (tenant_id,document_id,version_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE dm.upload_command_outcomes (
    tenant_id varchar(256) NOT NULL, actor_issuer varchar(2048) NOT NULL, actor_subject varchar(256) NOT NULL,
    operation varchar(64) NOT NULL, resource_scope text NOT NULL, idempotency_key varchar(128) NOT NULL,
    canonical_body_hash char(64) NOT NULL CHECK (canonical_body_hash ~ '^[0-9a-f]{64}$'),
    status integer NOT NULL CHECK (status IN (200,201)), upload_id uuid NOT NULL, receipt_id uuid,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,actor_issuer,actor_subject,operation,resource_scope,idempotency_key),
    FOREIGN KEY (tenant_id,upload_id,actor_issuer,actor_subject) REFERENCES dm.upload_slots (tenant_id,upload_id,actor_issuer,actor_subject),
    FOREIGN KEY (tenant_id,upload_id,receipt_id) REFERENCES dm.upload_receipts (tenant_id,upload_id,receipt_id)
);
CREATE TABLE dm.upload_cleanup_work (
    tenant_id varchar(256) NOT NULL, work_id uuid NOT NULL, upload_id uuid NOT NULL, document_id uuid NOT NULL,
    bucket varchar(63) NOT NULL CHECK (bucket IN ('dkp-quarantine','dkp-originals')),
    object_key varchar(138) NOT NULL CHECK (object_key ~ '^[0-9a-f]{64}/[0-9a-f-]{36}/[0-9a-f-]{36}$' AND split_part(object_key,'/',1)=encode(sha256(convert_to(tenant_id,'UTF8')),'hex') AND split_part(object_key,'/',2)=document_id::text), version_id varchar(1024) CHECK (length(version_id)>0 AND octet_length(version_id)<=1024 AND version_id <> 'null'),
    reason varchar(32) NOT NULL CHECK (reason IN ('REJECTED','EXPIRED','TRANSFER_ORPHAN','FAILED_VERIFIED_COPY','VERIFIED_QUARANTINE_COPY')),
    created_at timestamptz NOT NULL, attempt_count integer NOT NULL CHECK (attempt_count >= 0), next_attempt_at timestamptz NOT NULL,
    lease_id uuid, lease_until timestamptz, completed_at timestamptz,
    PRIMARY KEY (tenant_id,work_id),
    UNIQUE NULLS NOT DISTINCT (tenant_id,upload_id,bucket,object_key,version_id),
    FOREIGN KEY (tenant_id,document_id,upload_id) REFERENCES dm.upload_slots (tenant_id,document_id,upload_id),
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE TABLE dm.upload_deletion_fences (
    tenant_id varchar(256) NOT NULL, document_id uuid NOT NULL,
    attempt_id uuid NOT NULL, upload_id uuid NOT NULL,
    bucket varchar(63) NOT NULL CHECK (bucket IN ('dkp-quarantine','dkp-originals')),
    object_key varchar(138) NOT NULL CHECK (object_key ~ '^[0-9a-f]{64}/[0-9a-f-]{36}/[0-9a-f-]{36}$' AND split_part(object_key,'/',1)=encode(sha256(convert_to(tenant_id,'UTF8')),'hex') AND split_part(object_key,'/',2)=document_id::text),
    version_id varchar(1024) NOT NULL CHECK (length(version_id)>0 AND octet_length(version_id)<=1024 AND version_id <> 'null'),
    executor_instance_id uuid NOT NULL, created_at timestamptz NOT NULL,
    phase varchar(16) NOT NULL CHECK (phase IN ('AUTHORIZED','DISPATCHED','SETTLED_SUCCESS','SETTLED_FAILURE')),
    PRIMARY KEY (tenant_id,attempt_id),
    FOREIGN KEY (tenant_id,document_id) REFERENCES dm.documents,
    FOREIGN KEY (tenant_id,upload_id) REFERENCES dm.upload_slots
);
CREATE UNIQUE INDEX upload_one_unresolved_document_fence ON dm.upload_deletion_fences (tenant_id,document_id) WHERE phase IN ('AUTHORIZED','DISPATCHED');
CREATE TABLE dm.upload_deletion_settlements (
    tenant_id varchar(256) NOT NULL, attempt_id uuid NOT NULL,
    sdk_request_id varchar(1024) NOT NULL, response_classification varchar(64) NOT NULL,
    observed_at timestamptz NOT NULL,
    no_further_execution boolean NOT NULL CHECK (no_further_execution),
    authoritative_absence boolean NOT NULL,
    PRIMARY KEY (tenant_id,attempt_id),
    FOREIGN KEY (tenant_id,attempt_id) REFERENCES dm.upload_deletion_fences
);
CREATE TABLE dm.upload_audit_facts (
    event_id uuid NOT NULL PRIMARY KEY, tenant_id varchar(256) NOT NULL, document_id uuid NOT NULL, upload_id uuid NOT NULL,
    actor_issuer varchar(2048) NOT NULL, actor_subject varchar(256) NOT NULL,
    operation varchar(64) NOT NULL, state_revision bigint NOT NULL CHECK (state_revision BETWEEN 0 AND 9007199254740991),
    occurred_at timestamptz NOT NULL, outcome_code varchar(64) NOT NULL,
    FOREIGN KEY (tenant_id,document_id,upload_id) REFERENCES dm.upload_slots (tenant_id,document_id,upload_id)
);
-- State CAS and immutable ownership are enforced even for direct runtime DML.
CREATE FUNCTION dm.guard_upload_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.tenant_id,NEW.upload_id,NEW.document_id,NEW.actor_issuer,NEW.actor_subject,
        NEW.created_at,NEW.expires_at,NEW.expected_document_revision,NEW.declared_format,NEW.declared_size)
       IS DISTINCT FROM
       (OLD.tenant_id,OLD.upload_id,OLD.document_id,OLD.actor_issuer,OLD.actor_subject,
        OLD.created_at,OLD.expires_at,OLD.expected_document_revision,OLD.declared_format,OLD.declared_size)
       OR NEW.state_revision <> OLD.state_revision+1
       OR NEW.transient_reservation > OLD.transient_reservation
       OR (OLD.quarantine_cleanup_confirmed AND NOT NEW.quarantine_cleanup_confirmed)
       OR NEW.attempt_count NOT BETWEEN OLD.attempt_count AND OLD.attempt_count+1
       OR NEW.transfer_attempt_count NOT BETWEEN OLD.transfer_attempt_count AND OLD.transfer_attempt_count+1 THEN
        RAISE EXCEPTION 'Invalid upload transition' USING ERRCODE='23514';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
       (OLD.state='INITIALIZED' AND NEW.state='RECEIVING') OR
       (OLD.state='RECEIVING' AND NEW.state IN ('INITIALIZED','QUARANTINED')) OR
       (OLD.state='QUARANTINED' AND NEW.state='VERIFYING') OR
       (OLD.state='VERIFYING' AND NEW.state IN ('QUARANTINED','VERIFIED')) OR
       (OLD.state='VERIFIED' AND NEW.state='CONSUMED') OR
       (OLD.state NOT IN ('CONSUMED','REJECTED','EXPIRED') AND NEW.state IN ('REJECTED','EXPIRED'))) THEN
        RAISE EXCEPTION 'Invalid upload state edge' USING ERRCODE='23514';
    END IF;
    IF OLD.body_sha256 IS NOT NULL AND (NEW.body_sha256,NEW.actual_size,NEW.quarantine_bucket,NEW.quarantine_key,NEW.quarantine_version_id)
       IS DISTINCT FROM (OLD.body_sha256,OLD.actual_size,OLD.quarantine_bucket,OLD.quarantine_key,OLD.quarantine_version_id) THEN
        RAISE EXCEPTION 'Immutable upload source' USING ERRCODE='23514';
    END IF;
    IF OLD.possible_original_version_id IS NOT NULL AND NEW.possible_original_version_id IS DISTINCT FROM OLD.possible_original_version_id
       AND NOT (NEW.possible_original_key IS DISTINCT FROM OLD.possible_original_key AND OLD.copy_write_settled AND OLD.copy_cleanup_confirmed) THEN
        RAISE EXCEPTION 'Immutable known original version' USING ERRCODE='23514';
    END IF;
    IF OLD.possible_original_key IS NOT NULL AND NEW.possible_original_key IS DISTINCT FROM OLD.possible_original_key
       AND NOT (OLD.copy_write_settled AND OLD.copy_cleanup_confirmed) THEN
        RAISE EXCEPTION 'Unsettled upload copy' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_upload_slot BEFORE UPDATE ON dm.upload_slots FOR EACH ROW EXECUTE FUNCTION dm.guard_upload_slot();

CREATE FUNCTION dm.guard_cleanup_work() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.tenant_id,NEW.work_id,NEW.upload_id,NEW.document_id,NEW.bucket,NEW.object_key,NEW.version_id,NEW.reason,NEW.created_at)
       IS DISTINCT FROM (OLD.tenant_id,OLD.work_id,OLD.upload_id,OLD.document_id,OLD.bucket,OLD.object_key,OLD.version_id,OLD.reason,OLD.created_at)
       OR (OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at)
       OR NEW.attempt_count NOT BETWEEN OLD.attempt_count AND OLD.attempt_count+1 THEN
        RAISE EXCEPTION 'Immutable cleanup identity or completion' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_cleanup_work BEFORE UPDATE ON dm.upload_cleanup_work FOR EACH ROW EXECUTE FUNCTION dm.guard_cleanup_work();

CREATE FUNCTION dm.guard_upload_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM dm.upload_slots s JOIN dm.upload_verification_facts v
         ON v.tenant_id=s.tenant_id AND v.upload_id=s.upload_id AND v.document_id=s.document_id
         WHERE s.tenant_id=NEW.tenant_id AND s.upload_id=NEW.upload_id AND v.evidence_id=NEW.verification_evidence_id
           AND NEW.expires_at=s.expires_at AND NEW.created_at>=v.inspected_at AND NEW.created_at>=v.scanned_at
           AND NEW.created_at>=v.verified_at AND s.copy_write_settled
           AND NEW.original_bucket='dkp-originals' AND NEW.original_key=s.possible_original_key AND NEW.original_version_id=s.possible_original_version_id
           AND NEW.sha256=v.source_sha256 AND NEW.size_bytes=v.source_size
           AND NEW.detected_format=v.detected_format AND NEW.detected_mime=v.detected_mime
           AND s.body_sha256=v.source_sha256 AND s.actual_size=v.source_size
           AND (s.quarantine_bucket,s.quarantine_key,s.quarantine_version_id)=(v.source_bucket,v.source_key,v.source_version_id)) THEN
        RAISE EXCEPTION 'Invalid receipt evidence binding' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_upload_receipt BEFORE INSERT ON dm.upload_receipts FOR EACH ROW EXECUTE FUNCTION dm.guard_upload_receipt();

CREATE FUNCTION dm.guard_document_hold_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM dm.documents WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM dm.upload_deletion_fences WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id AND phase IN ('AUTHORIZED','DISPATCHED')) THEN
        RAISE EXCEPTION 'Document deletion unresolved' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_document_hold_fence BEFORE INSERT ON dm.legal_holds FOR EACH ROW EXECUTE FUNCTION dm.guard_document_hold_fence();

CREATE FUNCTION dm.guard_deletion_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM dm.documents WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id FOR UPDATE;
    IF TG_OP='INSERT' THEN
        IF NEW.phase<>'AUTHORIZED' OR EXISTS (SELECT 1 FROM dm.legal_holds WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id)
          OR NOT EXISTS (SELECT 1 FROM dm.upload_slots WHERE tenant_id=NEW.tenant_id AND upload_id=NEW.upload_id AND document_id=NEW.document_id)
          OR EXISTS (SELECT 1 FROM dm.upload_receipts r JOIN dm.upload_receipt_consumptions c USING (tenant_id,receipt_id)
             WHERE r.tenant_id=NEW.tenant_id AND (r.original_bucket,r.original_key,r.original_version_id)=(NEW.bucket,NEW.object_key,NEW.version_id)) THEN
            RAISE EXCEPTION 'Deletion ineligible' USING ERRCODE='55000';
        END IF;
    ELSE
        IF (NEW.tenant_id,NEW.document_id,NEW.attempt_id,NEW.upload_id,NEW.bucket,NEW.object_key,NEW.version_id,NEW.executor_instance_id,NEW.created_at)
           IS DISTINCT FROM (OLD.tenant_id,OLD.document_id,OLD.attempt_id,OLD.upload_id,OLD.bucket,OLD.object_key,OLD.version_id,OLD.executor_instance_id,OLD.created_at)
           OR NOT ((OLD.phase='AUTHORIZED' AND NEW.phase='DISPATCHED') OR (OLD.phase='DISPATCHED' AND NEW.phase IN ('SETTLED_SUCCESS','SETTLED_FAILURE'))) THEN
            RAISE EXCEPTION 'Invalid deletion attempt transition' USING ERRCODE='23514';
        END IF;
        IF NEW.phase IN ('SETTLED_SUCCESS','SETTLED_FAILURE') AND NOT EXISTS (
           SELECT 1 FROM dm.upload_deletion_settlements WHERE tenant_id=NEW.tenant_id AND attempt_id=NEW.attempt_id
             AND response_classification=NEW.phase AND no_further_execution AND (NEW.phase='SETTLED_FAILURE' OR authoritative_absence)) THEN
            RAISE EXCEPTION 'Deletion not settled' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_deletion_fence BEFORE INSERT OR UPDATE ON dm.upload_deletion_fences FOR EACH ROW EXECUTE FUNCTION dm.guard_deletion_fence();
CREATE FUNCTION dm.guard_receipt_consumption() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM dm.documents WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM dm.upload_receipts r JOIN dm.versions v
       ON v.tenant_id=r.tenant_id AND v.document_id=r.document_id
       WHERE r.tenant_id=NEW.tenant_id AND r.receipt_id=NEW.receipt_id AND v.version_id=NEW.version_id
         AND r.expires_at>NEW.consumed_at AND r.sha256=v.sha256 AND r.detected_mime=v.mime_type
         AND v.artifact_reference=r.receipt_id::text
         AND r.actor_issuer=v.creator_issuer AND r.actor_subject=v.creator_subject)
       OR EXISTS (SELECT 1 FROM dm.upload_deletion_fences f JOIN dm.upload_receipts r
          ON f.tenant_id=r.tenant_id AND (f.bucket,f.object_key,f.version_id)=(r.original_bucket,r.original_key,r.original_version_id)
          WHERE r.tenant_id=NEW.tenant_id AND r.receipt_id=NEW.receipt_id AND f.phase IN ('AUTHORIZED','DISPATCHED')) THEN
        RAISE EXCEPTION 'Invalid receipt consumption' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER guard_receipt_consumption AFTER INSERT ON dm.upload_receipt_consumptions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dm.guard_receipt_consumption();
REVOKE ALL ON FUNCTION dm.guard_cleanup_work(),dm.guard_upload_slot(),dm.guard_upload_receipt(),dm.guard_document_hold_fence(),dm.guard_deletion_fence(),dm.guard_receipt_consumption() FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA dm FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON dm.upload_resource_accounts,dm.upload_global_resource_account,dm.upload_slots,dm.upload_cleanup_work,dm.upload_deletion_fences TO dkp_dm_runtime;
GRANT SELECT,INSERT ON dm.upload_receipts,dm.upload_verification_facts,dm.upload_receipt_consumptions,dm.upload_command_outcomes,dm.upload_audit_facts,dm.upload_deletion_settlements TO dkp_dm_runtime;
