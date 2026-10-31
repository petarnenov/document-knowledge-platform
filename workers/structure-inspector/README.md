# Isolated structural inspector

Independent Java25 process: PDFBox3.0.8 and bounded JDK ZIP/StAX validation; no DM, database, storage SDK or outbound HTTP dependency. This is structural evidence only. Real standard ClamAV verification is independently required before any upload receipt.

Run `:workers:structure-inspector:check` to test all four synthetic document families, active/encrypted/malformed input, ZIP/XML attacks, actual TLS, authentication, admission, bounded input and the watchdog. Coverage gates require75% lines and branches. These tests do not claim operating-system sandbox acceptance; the real Compose/CI gate must prove non-root/read-only/resource/no-egress behavior.

Application main: `org.documentknowledge.inspector.InspectorApplication`, fixed TLS port8443. Required environment paths:

- `DKP_INSPECTOR_KEYSTORE_PATH`: private PKCS12 server key/certificate with the actual service DNS SAN.
- `DKP_INSPECTOR_KEYSTORE_PASSWORD_PATH`: private UTF8 password text (terminal newline allowed).
- `DKP_INSPECTOR_SERVICE_KEY_PATH`: private standard-Base64 text encoding exactly32 random bytes (terminal newline allowed).
- `DKP_INSPECTOR_TEMP_DIR`: dedicated private0700 directory/tmpfs, at most1GB. Startup reclaims stale `inspection-*` content without following links and refuses unexpected content.

Secret files/ancestors must be nonsymlinked and not group/other writable; secret files deny all group/other permissions. Mount secrets read-only with narrowly scoped ownership. The worker receives no browser, IdP, S3 or PostgreSQL credentials, no definitions volume, and no outbound network capability. Root runtime deployment owns CA issuance, network enforcement, CPU/memory/PID/read-only/tmpfs constraints and restart policy.

`POST /internal/v1/inspect` accepts only `application/octet-stream`, one `X-DKP-Format` (`PDF|DOCX|PPTX|XLSX`) and `Authorization: Bearer <base64-service-key>`. No query, path, caller URL, tool selection or extra X-DKP selector is allowed. Success is the exact frozen inspection-result JSON. Max source100000000 bytes, one admitted inspection, fixed60s watchdog terminates the process on an unresponsive body/parser; dependency errors never attest clean content. PDF scratch is separately capped900000000 bytes in its disposable per-request directory. HTTP clients independently verify the CA, hostname, source size/hash, format/MIME, reviewed profile digest and configured image digest.

TLS-only `GET /health/liveness` and `/health/readiness` disclose only process status. Actual sandbox/egress proof must use a controlled canary from this container's exact network namespace; there is deliberately no production arbitrary-network test route.
