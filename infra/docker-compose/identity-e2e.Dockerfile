FROM node:24.22.1-bookworm-slim@sha256:be8204d4bd327363357f532187b752ff62759c31bedf98e9e18fc08c9cbd605c AS node-toolchain

FROM mcr.microsoft.com/playwright:v1.64.1-noble@sha256:47b510dd9230bf0b9bfc9b20102241e577ea7a99334ab4c3ca19f19770e9b8ea
# A disposable trusted-development test runner, never a production component.
# NSS package selection: https://packages.ubuntu.com/noble-updates/libnss3-tools
COPY --from=node-toolchain /usr/local/bin/node /usr/local/bin/node
RUN apt-get update \
    && apt-get install --yes --no-install-recommends libnss3-tools=2:3.98-1ubuntu0.3 \
    && rm -rf /var/lib/apt/lists/* \
    && npm install --global pnpm@12.16.1
WORKDIR /workspace
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY patches/ patches/
COPY apps/admin-portal/package.json apps/admin-portal/package.json
RUN pnpm install --frozen-lockfile
COPY generated-clients/typescript generated-clients/typescript
COPY apps/admin-portal apps/admin-portal
COPY tools/runtime/synthetic-documents.mjs tools/runtime/synthetic-documents.mjs
RUN node tools/runtime/synthetic-documents.mjs
RUN <<'BUILD'
set -eu
cat > /workspace/apps/admin-portal/playwright.identity.runtime.config.ts <<'CONFIG'
import base from './playwright.identity.config';
const address = process.env.DKP_IDENTITY_IDP_ADDRESS;
if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(address ?? '') || address!.split('.').some((part) => Number(part) > 255)) {
  throw new Error('An explicit Compose identity-provider IPv4 address is required.');
}
export default {
  ...base,
  use: {
    ...base.use,
    launchOptions: {
      // Name resolution only: HTTPS still verifies idp.localhost and its CA.
      args: [`--host-resolver-rules=MAP idp.localhost ${address}`],
    },
  },
};
CONFIG
cat > /workspace/apps/admin-portal/browser-trust-smoke.mjs <<'TRUST'
import { chromium } from '@playwright/test';
let browser;
try {
  const address = process.env.DKP_IDENTITY_IDP_ADDRESS;
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(address ?? '') || address.split('.').some((part) => Number(part) > 255)) throw new Error('Invalid provider address');
  browser = await chromium.launch({ args: [`--host-resolver-rules=MAP idp.localhost ${address}`] });
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  const page = await context.newPage();
  const portal = new URL(process.env.DKP_IDENTITY_BASE_URL);
  const provider = new URL(process.env.DKP_IDENTITY_IDP_BASE_URL);
  if (portal.protocol !== 'https:' || portal.hostname !== 'localhost' || provider.protocol !== 'https:' || provider.hostname !== 'idp.localhost' || portal.username || portal.password || provider.username || provider.password) throw new Error('Invalid trusted local origins');
  for (const url of [new URL('/', portal), new URL('/realms/dkp/.well-known/openid-configuration', provider)]) {
    const response = await page.goto(url.href);
    if (response?.status() !== 200) throw new Error('Expected trusted HTTPS response');
  }
  let denied = false;
  try { await page.goto(`https://wrong-name.localhost:${portal.port}/`); }
  catch (error) { denied = error.message.includes('ERR_CERT_COMMON_NAME_INVALID'); }
  if (!denied) throw new Error('Wrong certificate hostname was not denied');
  console.log('Strict Chromium CA/hostname verification passed.');
} catch {
  console.error('Strict Chromium CA/hostname verification failed.');
  process.exitCode = 1;
} finally { await browser?.close(); }
TRUST
cat > /usr/local/bin/dkp-identity-e2e <<'RUNNER'
#!/bin/bash
set -euo pipefail
umask 077
# Only the public CA enters the test container; no private key or realm secret.
install -m 0644 /run/dkp/ca.crt /usr/local/share/ca-certificates/dkp-local.crt
update-ca-certificates >/dev/null 2>&1
export NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/dkp-local.crt
DKP_BROWSER_HOME=$(getent passwd pwuser | cut -d: -f6)
mkdir -p "$DKP_BROWSER_HOME/.local/share/pki/nssdb"
chown -R pwuser:pwuser "$DKP_BROWSER_HOME"
# Chromium M146+ reads the NSS Shared DB here; it is removed with the container.
runuser -u pwuser -- certutil -N -d "sql:$DKP_BROWSER_HOME/.local/share/pki/nssdb" --empty-password
runuser -u pwuser -- certutil -A -d "sql:$DKP_BROWSER_HOME/.local/share/pki/nssdb" -n DKP-local-test-CA -t 'C,,' -i "$NODE_EXTRA_CA_CERTS"
# Verify Node-side HTTPS through the same CA before running the browser.
runuser -u pwuser -- node --input-type=module -e 'try { const response = await fetch(process.env.DKP_IDENTITY_BASE_URL); if (!response.ok) process.exit(1); } catch { console.error("Portal HTTPS trust verification failed."); process.exit(1); }'
runuser -u pwuser -- node /workspace/apps/admin-portal/browser-trust-smoke.mjs
cd /workspace/apps/admin-portal
exec runuser -u pwuser -- node /workspace/apps/admin-portal/node_modules/@playwright/test/cli.js test --config playwright.identity.runtime.config.ts "$@"
RUNNER
chmod 0555 /usr/local/bin/dkp-identity-e2e
chown -R pwuser:pwuser /workspace/apps/admin-portal
BUILD
ENTRYPOINT ["/usr/local/bin/dkp-identity-e2e"]
