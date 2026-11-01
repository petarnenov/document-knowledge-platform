# Verification matrix — proposed, no implementation acceptance

| Area | Required evidence |
| --- | --- |
| Primary capability | Exact5.2.20 source hashes and compiled public signatures; real public-setter probe leaves config/identity unchanged before any body; no state getters/event |
| Descriptor | Strict JSON Schema2020-12 positive and closed negative cases for version/hash/class/field/type/modifier/value/order/extra fields, prohibited writes/fallback/opener/callback omissions |
| Startup | Correct selected unshaded JVM artifact succeeds; wrong/absent/modified artifact, wrong class/loader/layout, missing/denied field or null limits fails closed; no global opens flags |
| Same-instance state | Separately change decompression true, queue16384/131073/unlimited, body99999999/100000001/unlimited/null after init; external config and handler identity unchanged; reject before Micronaut reads/allocates/protected work |
| Callback coverage | Before first byte, between raw and decoded guards, before channelReadComplete streaming construction, before exceptionCaught devolve-to-streaming, and after a previous request; source-derived path inventory and actual counters |
| Pipeline/owner | Wrong handler identity, guard removal/reorder, executor hop, inserted mutator between guard and actual handler, unsupported callback/channel/protocol; close/latch, not setter repair |
| Readiness/lifecycle | Mismatch/denied read permanently latches current runtime unavailable; new channels cannot clear it; owned message release and framework close/discard settle without asserting remote/SQL rollback |
| Preserved behavior | Full original341 fixed/chunked32,400,444-byte source, blocked consumer, legal pipelining count and bounded queue/bridges, unsupported profiles, auth/tenant/Origin/CSRF, metadata16384/binary100000000, transfer300s/idle30s, finite errors |
| Production containment | New outer adapter only; no domain/application dependency inversion; no private stores or general reflection surface; each changed production file/area75% line/branch with meaningful negatives |
| Delivery | Exact-source independent review/currentCI; actual nonroot Linux channel/native/provider/HTTPS, aggregate222 and final elapsed-hour gates. Formal architecture/security/CID/production decisions remain separate |

Research evidence: `contracts/probes/PublicIngressPolicyProbe.java` compiled and ran on cached repository JDK25.0.5 with the actual338 pinned runtime dependency classpath; three exact PROBE_PASS observations, no socket/body/private-field access. Probe logs and binary/source hashes are recorded in contracts/evidence.md. Public-method inspection in this research fixture is not a production private-state implementation. Production behavior in the table is prospective and unexecuted.

The proposed read-only mechanism is accepted only if legal competing customizers cannot change the selected state during an unguarded body-capable path. If the normal supported model violates that assumption or a source/loader upgrade removes a checkable state, stop and return to review. No continuous-monitoring or malicious-in-process-code sandbox claim is made.
