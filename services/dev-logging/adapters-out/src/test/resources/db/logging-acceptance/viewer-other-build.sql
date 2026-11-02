-- Disposable-only second build for real viewer filter acceptance; never production ingestion.
INSERT INTO dev_logs.events(producer_id,scope_id,event_id,build_id,run_id,instance_id,component,git_sha,source_fingerprint,
 event_time,received_at,severity,event_name,correlation_id,scope_kind,canonical_hash,canonical_payload,retain_until,reserved_bytes)
SELECT producer_id,scope_id,'88888888-8888-4888-8888-888888888888'::uuid,'99999999-9999-4999-8999-999999999999'::uuid,
 run_id,instance_id,component,git_sha,source_fingerprint,event_time,clock_timestamp(),severity,event_name,correlation_id,scope_kind,
 repeat('d',64),convert_to((convert_from(canonical_payload,'UTF8')::jsonb || jsonb_build_object('eventId','88888888-8888-4888-8888-888888888888','buildId','99999999-9999-4999-8999-999999999999'))::text,'UTF8'),retain_until,reserved_bytes
FROM dev_logs.events WHERE event_id='44444444-4444-4444-8444-444444444444';
