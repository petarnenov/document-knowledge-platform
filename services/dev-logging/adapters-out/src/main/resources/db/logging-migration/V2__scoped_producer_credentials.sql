-- Additive migration; retained V1 records never acquire implicit credential authority.
ALTER TABLE dev_logs.scopes ADD COLUMN credential_sha256 varchar(64)
    CHECK (credential_sha256 IS NULL OR credential_sha256 ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX scope_credential_sha256 ON dev_logs.scopes(credential_sha256)
    WHERE credential_sha256 IS NOT NULL;
