import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { loggingManifest } from './tooling/logging-manifest.js';

export default defineConfig({
  plugins: [react(), loggingManifest(new URL('../../', import.meta.url).pathname, process.env.DKP_LOG_BUILD_MANIFEST)],
  build: {
    rolldownOptions: {
      input: {
        portal: new URL('./index.html', import.meta.url).pathname,
        logs: new URL('./logs.html', import.meta.url).pathname,
      },
    },
  },
  server: { host: '127.0.0.1', port: 5173, strictPort: true },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.tsx'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}', 'tooling/**/*.ts'],
      exclude: ['src/**/*.test.tsx', 'src/test-setup.ts'],
      reporter: ['text', 'lcov', 'json-summary'],
      thresholds: { lines: 75, branches: 75, functions: 75, statements: 75 },
    },
  },
});
