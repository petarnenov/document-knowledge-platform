## ADDED Requirements

### Requirement: Singleton generic temporary ownership wiring

The upload runtime SHALL wire one durable generic application port shared by TRANSFER and ReceiptArtifactAuthority PREAPPEND. Its namespace/roles/limits SHALL remain distinct from verification, content and remote cleanup. Missing binding SHALL fail closed without a Path-only fallback. Disabled legacy upload mode SHALL remain unchanged; no domain table or cross-database write SHALL be added.

#### Scenario: Inspect actual factory
- **WHEN** uploads are enabled
- **THEN** both generic allocation families SHALL share the same process owner
- **AND** separate verification/content reservations SHALL NOT reset or cover their capacity

### Requirement: Truthful generic namespace readiness and shutdown

Startup SHALL validate the closed namespace/lock and reconcile only inactive owned records before readiness. Active valid work SHALL stay healthy; retained/invalid state SHALL make upload readinessDOWN while liveness remains unaffected. Shutdown SHALL stop admission and revoke progress while retaining ownership until actual local settlement; no timer-only lock handoff SHALL permit another process to act.

#### Scenario: Restart after source unlink failure
- **WHEN** a process exits with owned generic bytes retained
- **THEN** a replacement SHALL acquire the actual lock and perform bounded owned recovery before reuse
- **AND** SQL/S3 quota zero, lease expiry and same-key requests SHALL NOT restore local readiness by themselves
