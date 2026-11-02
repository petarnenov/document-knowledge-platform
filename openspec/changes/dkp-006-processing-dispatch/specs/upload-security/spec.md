## MODIFIED Requirements

### Requirement: Honest staged processing boundary
Upload completion SHALL remain verified-receipt-only with no version. Only after reviewed BP05 cutover SHALL additive202 processing acceptance and atomic NEW legacy append scheduling activate. Legacy201 contracts/replays SHALL remain exact. BP05 SHALL require version/run/intent/outbox atomicity and explicit old-version coverage, never fabricated processing readiness.

#### Scenario: Upload succeeds before workflows exist
- **WHEN** a verified upload is explicitly appended in BP03
- **THEN** immutable UNREVIEWED version exists but no processing/publication success or active pointer change is fabricated

#### Scenario: Receipt completion remains separate
- **WHEN** upload completes before processing acceptance
- **THEN** only the durable verified receipt exists and no version or processing-started status is invented

#### Scenario: Reviewed cutover compatibility
- **WHEN** BP05 is approved, dependency gates pass and the one-way writer fence activates
- **THEN** all NEW versions use the atomic quad; old201 keys remain201 and old versions are explicitly inventoried/enrolled without immutable rewrites
