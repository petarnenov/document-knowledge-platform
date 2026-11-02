# Verification and acceptance matrix — DRAFT246

## Specification gates
Run officialOpenSpec1.16.0 strict, pinnedRedocly2.72.1 onbothOpenAPI3.1 documents, AsyncAPIparser3.6.6, strictAjv2020formats onallclosednewcomponent/event/workflow/resolution/catalogschemas. Validate positiveexamples and negativeunknownfields, malformedUUID/issuer/hash/enum/state/tenant, forgedauthority/content/token, budget andreceiptshape fixtures. Check copiedlegacyDMpaths/operationIds/status/DTOs/nonAction schemas deep-equal; soleAction enumdelta original10+approvedBP04share+3processingvalues; direct/BFF5parity. No generatedSDK/publication/runtime is performed in specification work. Realresults below are filled only afterexecution.

## Mandatory future runtime matrix
| ID | Actual required proof | Evidence/forbidden shortcut |
|---|---|---|
| A01 | PG every-write failure rollback for version/run/intent/outbox/receipt/command/audit; realCAS/parallelkey/bodyconflict/receiptonewinner | exactownedtables+balances, deferredguards, nosyntheticonlyacceptance |
| A02 | oldclient201/new202 compatibility, pre-cutoverreplay, completeoldversioninventory/enroll, versionbyteimmutability | Java/TSoldconsumercompile+realHTTPS, noauto/destructivebackfill |
| A03 | actualcommitACKloss/physicaldisconnect/deadline beforeSQL andaftercommit | finite503/originalkeyreconcile, nofreshkeyretry orfakeabsence |
| W01 | realTemporal down atacceptance, crashafterDMcommit, crashafterstartbeforemark, delayedstart+AlreadyStarted+wrongidentity | one exactexecution/run, permitsretainedonunknown, deterministicIDandinputbinding |
| W02 | twoactualdispatchers/fairtenants/50global/tenant5; leases/restart/starvation | actualremoteactive+possiblecountandDMconservation, no50heavyOCRclaim |
| W03 | realcheckpoint+waiting whenparserabsent; workerkill/restart+compatible/incompatiblehistoryreplay | no dummyparse/READY; fourgenuineformatoriginals retained |
| W04 | real30minaccepteddeadline, retry/queue/restartcannotextend; scopedcancelbothraceorders, latecallbackfenced | fastclockunitboundaryPLUSgenuineelapsedruntimecase whereapplicable; noDBTTLrewrite |
| E01 | realKafka outage/backlog/recovery; crashafterackbeforeSQLmark; duplicate/out-of-order/gaps | sameeventID+oneownedinboxeffect, no droppedrows/exactlyoncefiction |
| E02 | old/newevent/SDK/workflowcontracts, unsupportedversions, retainedoffsetrestore | actualschema+consumercompat, UNKNOWNpause/reconcile notdiscard |
| S01 | wrongCA/SAN/peer/role/topic/namespace/taskqueue/expiredcert/unsupportedexternaltrust | realTLSauthentication+authorization, noDefaultAuthoritiesALLOWorunscopedprivatebridge |
| S02 | currentacceptedactorrevocation/tenant/ACL/explicitDENY/classification/holdbeforestage/finalresponse | noartifact/checkpointpermissionfromreceipt; mixedtenantabsencecodesnoleak |
| S03 | actualworkerCPU/memory/PIDs/readOnly/temp/cap/egress andmaliciouscontent/profilelimits | no arbitraryplugin/URL/unscannedcontent; no disablingscanner/inspector |
| D01 | isolatedPGroles/nootherDB/deferredconstraints, immutableaudit/events/checkpoints, actualrestart/dumprestore | runtimecannotCREATE/DDL/rewritehistory/cutover; fsyncdurabilityreal |
| R01 | realTemporal/Kafka/PG/RustFS/OIDC/workeroutagesandrestarts+corruptcheckpoint recovery | distinctdependencysemantics; preserveoriginals/hold/accounting andcurrentauthority |
| R02 | originalsame-keyenrollment/cancel/acceptanceafterUNKNOWNandretainedold201 | exactlyoneoutcome; no hiddenstatus/processedclaim |
| U01 | genuineOIDC+CAHTTPSfourformatsnewacceptance+oldappend+oldversionenroll+wait+cancel/status | bothMantinethemes/keyboard/focus/contrast/cacheclear/reload/identityswitch, no mocks |
| P01 | measureauth/body/SQL/final500ms/whole8/9s/RPC/queue/cancellation atlimits | actualstalledbodies+rawRST/exhaustedpools, zero lateSQL/effects, nosleepbudgetfiction |
| O01 | signed/pinnedimmutableimages,SBOM/CVE/license/secretchecks,independentbuild/release/probes/runbooks | actualCI+ownedfreshstack, no productionapprovalfromdevsuccess |

Separate>=75% changed-source lineANDbranch coverage fordomain/application/persistence/inbound/Temporaladapter/Kafkaadapter/worker/BFF/portal/helperareas, meaningfulnegative/concurrencycases, no skips whenprovidersavailable. Aggregatelegacycoveragecannothideanuncoverednewadapter. Sourceunitgatescomplementactualproviderfaults/E2E, neverreplace them. Independentimplementationreview/actualfinalheadCI/devintegrationrequiredbeforepackageclosure. CID05/06/07/11/12/15remainseparatelytrackedformal/productiongates.

## Observed specification checks
Executed on 2026-11-01 with Node24.22.1 and the existing pinned tool dependencies from the isolated BP04 browser worktree; no dependency or lock changes:

- `openspec validate dkp-006-processing-dispatch --strict`: PASS. Official informational diagnostic: `upload-security` is not yet canonical, so archive must wait for the BP03 archive dependency; it is not suppressed.
- `redocly lint --config tools/contracts/redocly.yaml` on both candidate OpenAPI files: PASS, zero warnings. The repository minimal configuration is used; no license is invented.
- Actual AsyncAPI parser3.6.6: PASS, zero errors. It reports the advisory that AsyncAPI3.1.0 exists; this packet intentionally uses the already pinned supported3.0.0 format. No advisory is represented as zero diagnostics.
- `node contracts/validate.cjs <existing-dependency-root>`: PASS,52 strict schemas and105 positive/negative fixtures;22 published legacy paths plus two approved BP04 sharing paths preserved,10 additive direct/BFF paths checked, sole Action additions verified. The BP04 witness includes the approved193 publication source hash.

Original logs are local `/tmp/dkp246-{openspec,redocly,asyncapi,schema-compat}.log`; revised259 results are `/tmp/dkp259-{openspec-all,redocly,asyncapi,schema-compat}.log`. No generated SDK, provider, runtime, automated source coverage, CI or production gate was exercised by this specification work.

## Review253/issue259 protocol regressions

Mandatory implementation races: early worker before dispatcher ACK; Start accepted/ACK lost with dispatcher restart; different execution/input rejection; changed runRevision re-handoff without epoch increase; resolution/checkpoint commit followed by lost ACK and worker restart; current revocation on replay; compatible cross-epoch reuse versus stale absent write; changed resolution conflict; cancellation before late callback; interleaved two-run events/document metadata updates without false gaps; duplicate and out-of-order per-run event sequences. Frozen protocol fixtures and validator assertions check the proposed examples and field coupling only; actual Temporal/PG/Kafka races remain mandatory future gates.

Correction259 verification re-executes the same strict tools;23 finite protocol examples and envelope-identity binding checks supplement105 wire fixtures. These reference examples are not evidence of actual concurrency, network, database or Temporal execution. Original30minute/50-workflow and every prior acceptance gate remain required.
