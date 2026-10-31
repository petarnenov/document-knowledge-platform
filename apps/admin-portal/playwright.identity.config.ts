import { defineConfig, devices } from '@playwright/test';

if (
  !process.env.DKP_IDENTITY_BASE_URL ||
  !process.env.DKP_IDENTITY_USERNAME ||
  !process.env.DKP_IDENTITY_PASSWORD
) {
  throw new Error(
    'Real identity E2E requires DKP_IDENTITY_BASE_URL, DKP_IDENTITY_USERNAME and DKP_IDENTITY_PASSWORD.',
  );
}
if (new URL(process.env.DKP_IDENTITY_BASE_URL).protocol !== 'https:') {
  throw new Error('Real identity E2E requires a trusted HTTPS base URL.');
}

export default defineConfig({
  testDir: './e2e-real',
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: 'dot',
  timeout: 60_000,
  use: {
    baseURL: process.env.DKP_IDENTITY_BASE_URL,
    // The isolated test runtime must trust the development CA. Never bypass TLS.
    ignoreHTTPSErrors: false,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [
    { name: 'real-oidc-chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
