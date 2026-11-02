## ADDED Requirements

### Requirement: Fixed compatible processing delegation
BFF SHALL provide only the five frozen processing mirrors with server-selected tenant/original bearer, Origin+CSRF,9s operation/16KiB request/32KiB response and current final session checks. Legacy DM/upload/KB routes and budgets SHALL remain unchanged; no arbitrary URL or forwarded actor authority is accepted.

#### Scenario: Guard and disconnect
- **WHEN** authenticated staff accepts/cancels or a browser connection aborts
- **THEN** real guarded HTTPS uses the fixed DM peer; owner cancels pending work, finite errors/singleton no-store persist and late success is withheld

### Requirement: Honest accessible processing status
Mantine portal SHALL distinguish durable accepted/pending/dispatched/waiting/blocked/cancelling/terminal and NOT_SCHEDULED legacy versions without claiming parsing or publication. It SHALL preserve exact original-key reconciliation and identity/tenant cache clearing with accessible both-theme keyboard/error journeys.

#### Scenario: Old version and lost response
- **WHEN** staff enrolls a NOT_SCHEDULED version or retries an ambiguous acceptance
- **THEN** same exact command is reconciled, current status is independently authorized and no duplicate run or fabricated READY appears
