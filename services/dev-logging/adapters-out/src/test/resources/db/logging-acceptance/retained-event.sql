-- Synthetic fixture only; explicit acceptance project, never an ingestion/producer claim.
INSERT INTO dev_logs.events(producer_id,scope_id,event_id,build_id,run_id,instance_id,component,git_sha,source_fingerprint,
 event_time,received_at,severity,event_name,correlation_id,scope_kind,canonical_hash,canonical_payload,retain_until,reserved_bytes)
VALUES ('22222222-2222-4222-8222-222222222222','33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444',
 '11111111-1111-4111-8111-111111111111','55555555-5555-4555-8555-555555555555','66666666-6666-4666-8666-666666666666',
 'runtime-tools',repeat('a',40),repeat('b',64),clock_timestamp(),clock_timestamp(),'DEBUG','runtime.started',
 '77777777-7777-4777-8777-777777777777','OPERATOR_ONLY',repeat('c',64),
 convert_to('{"eventId":"44444444-4444-4444-8444-444444444444","buildId":"11111111-1111-4111-8111-111111111111","runId":"55555555-5555-4555-8555-555555555555","instanceId":"66666666-6666-4666-8666-666666666666","component":"runtime-tools","gitSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sourceFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","timestamp":"2026-11-02T10:00:00Z","severity":"DEBUG","eventName":"runtime.started","correlationId":"77777777-7777-4777-8777-777777777777","context":{"phase":"runtime.read-acceptance","outcome":"SUCCEEDED"}}','UTF8'),clock_timestamp()+interval '8 days',2048);
