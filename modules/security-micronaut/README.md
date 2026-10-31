# Resource API security adapter

This module implements strict bearer-token and tenant-context enforcement for domain APIs. Its validation, signing-key source and safe rejection beans are unconditional whenever the module is present: a configuration value cannot remove the strict policies and fall back to framework defaults. Trust is usable only with `dkp.security.mode=resource` and the complete safe profile; missing or unsupported mode closes protected authentication.

The browser BFF uses its separate OIDC/session adapters and does not depend on this resource-only adapter module. Share immutable technical contracts through `identity-contracts`, rather than mixing browser and API token policies.
