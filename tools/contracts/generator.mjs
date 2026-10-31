import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const generatorVersion = '7.26.0';
export const generatorUrl = `https://repo.maven.apache.org/maven2/org/openapitools/openapi-generator-cli/${generatorVersion}/openapi-generator-cli-${generatorVersion}.jar`;
// Calculated from the official Maven artifact; acquisition also matched Maven's published SHA-1.
export const generatorSha256 = '1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e';

export function verifyGeneratorBytes(bytes) {
  const actual = createHash('sha256').update(bytes).digest('hex');
  if (actual !== generatorSha256) throw new Error(`OpenAPI Generator ${generatorVersion} checksum mismatch: expected ${generatorSha256}, received ${actual}. No Java code was executed.`);
}

async function downloadArtifact() {
  const response = await fetch(generatorUrl, { redirect: 'error', signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error(`Generator download failed: HTTP ${response.status}`);
  return Buffer.from(await response.arrayBuffer());
}

export async function ensureGenerator({
  cacheDirectory = join(process.env.XDG_CACHE_HOME || join(homedir(), '.cache'), 'dkp-tools', 'openapi-generator'),
  download = downloadArtifact,
} = {}) {
  await mkdir(cacheDirectory, { recursive: true });
  const artifact = join(cacheDirectory, `openapi-generator-cli-${generatorVersion}.jar`);
  let cached;
  try {
    cached = await readFile(artifact);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (cached) {
    // Cached files are untrusted too: verify on every invocation before launching Java.
    verifyGeneratorBytes(cached);
    return artifact;
  }
  const bytes = await download();
  verifyGeneratorBytes(bytes);
  const temporary = await mkdtemp(join(cacheDirectory, '.download-'));
  try {
    const candidate = join(temporary, 'generator.jar');
    await writeFile(candidate, bytes, { mode: 0o600 });
    await rename(candidate, artifact);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
  return artifact;
}
