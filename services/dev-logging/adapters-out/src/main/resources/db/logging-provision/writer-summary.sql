-- Private aggregate preservation witness. Never select event payloads or credentials.
SELECT json_build_object(
    'manifests',(SELECT count(*) FROM dev_logs.manifests),
    'producers',(SELECT count(*) FROM dev_logs.producers),
    'scopes',(SELECT count(*) FROM dev_logs.scopes),
    'events',(SELECT count(*) FROM dev_logs.events),
    'capacityReservedBytes',(SELECT reserved_bytes FROM dev_logs.capacity WHERE singleton),
    'scopeReservedBytes',(SELECT coalesce(sum(reserved_bytes),0) FROM dev_logs.scopes),
    'eventReservedBytes',(SELECT coalesce(sum(reserved_bytes),0) FROM dev_logs.events)
);
