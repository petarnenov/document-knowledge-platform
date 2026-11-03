import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import type { Plugin } from 'vite';

const UUID = /^(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const HASH = /^[0-9a-f]{64}$/;
const KEYS = ['buildId', 'compiledAt', 'component', 'dependencyFingerprint', 'dirty', 'gitSha',
  'schemaVersion', 'sourceFingerprint', 'sourceFingerprintAlgorithm', 'toolchainFingerprint', 'vocabulary', 'vocabularySha256'];

function requireValid(condition: unknown): asserts condition {
  if (!condition) throw new Error('LOG_PORTAL_MANIFEST_INVALID');
}

// The original builder uses sorted ASCII keys and integer/boolean public metadata.
export function canonicalPublic(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalPublic).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const keys = Object.keys(record).sort();
    requireValid(keys.every(key => /^[\x20-\x7e]+$/.test(key)));
    return `{${keys.map(key => `${JSON.stringify(key)}:${canonicalPublic(record[key])}`).join(',')}}`;
  }
  requireValid(value === null || typeof value === 'string' || typeof value === 'boolean'
    || (typeof value === 'number' && Number.isSafeInteger(value)));
  return JSON.stringify(value);
}

/** Consumes an original builder output; never creates build/run identity or authority. */
export function readPortalManifest(root: string, file: string): string {
  requireValid(isAbsolute(file) && resolve(file) === file && realpathSync(file) === file);
  const buildId = dirname(file).split('/').at(-1);
  requireValid(typeof buildId === 'string' && UUID.test(buildId));
  requireValid(file === join(realpathSync(root), 'build/generated/dkp-logging/admin-portal', buildId, 'manifest.json'));
  const stat = lstatSync(file);
  requireValid(stat.isFile() && stat.size > 0 && stat.size <= 1024 * 1024);
  const raw = new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(file));
  const manifest: unknown = JSON.parse(raw);
  requireValid(manifest !== null && typeof manifest === 'object' && !Array.isArray(manifest));
  const record = manifest as Record<string, unknown>;
  requireValid(Object.keys(record).sort().join(',') === KEYS.join(',') && canonicalPublic(record) === raw);
  requireValid(record.schemaVersion === 1 && record.component === 'admin-portal' && record.buildId === buildId
    && record.sourceFingerprintAlgorithm === 'dkp-public-inputs-v1' && typeof record.dirty === 'boolean');
  requireValid(typeof record.gitSha === 'string' && /^[0-9a-f]{40}$/.test(record.gitSha));
  for (const key of ['sourceFingerprint', 'toolchainFingerprint', 'dependencyFingerprint', 'vocabularySha256']) {
    requireValid(typeof record[key] === 'string' && HASH.test(record[key]));
  }
  requireValid(typeof record.compiledAt === 'string' && Number.isFinite(Date.parse(record.compiledAt)));
  requireValid(record.vocabulary !== null && typeof record.vocabulary === 'object' && !Array.isArray(record.vocabulary));
  requireValid(createHash('sha256').update(canonicalPublic(record.vocabulary)).digest('hex') === record.vocabularySha256);
  return raw;
}

export function loggingManifest(root: string, file: string | undefined): Plugin {
  // Legacy builds remain explicitly unconfigured until their original-build adapter adopts this port.
  const raw = file === undefined ? undefined : readPortalManifest(root, file);
  return {
    name: 'dkp-original-logging-manifest',
    apply: 'build',
    transformIndexHtml() {
      if (raw === undefined) return [];
      return [{ tag: 'script', attrs: { id: 'dkp-log-build-manifest', type: 'application/json' },
        children: raw.replaceAll('<', '\\u003c'), injectTo: 'head-prepend' }];
    },
    generateBundle() {
      if (raw !== undefined) this.emitFile({ type: 'asset', fileName: 'dkp-logging-manifest.json', source: raw });
    },
  };
}
