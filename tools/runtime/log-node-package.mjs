/** Offline source-distribution preparation; syntax validation is not transpilation. */
import { lstatSync, realpathSync, readdirSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

export const MODULES = Object.freeze(['browser', 'node-https', 'node-spool', 'node', 'queue', 'responses', 'wire']);
export const FILES = Object.freeze(['package.json', ...[
  ...MODULES.map(name => 'src/' + name + '.js'),
  ...['browser', 'node-spool', 'node', 'types'].map(name => 'src/' + name + '.d.ts'),
].sort()]);
const sha = value => createHash('sha256').update(value).digest('hex');
function requireBuild(value) { if (!value) throw new Error('LOG_NODE_PACKAGE_INVALID'); }
function read(path) {
  const info = lstatSync(path);
  requireBuild(info.isFile() && !info.isSymbolicLink() && realpathSync(path) === path && info.size > 0 && info.size <= 65536);
  const data = readFileSync(path); requireBuild(data.length === info.size); return data;
}

export function packageDistribution(source, output) {
  requireBuild(process.version === 'v24.22.1' && process.getuid() > 0);
  for (const directory of [source, output]) requireBuild(typeof directory === 'string' && isAbsolute(directory)
    && resolve(directory) === directory && realpathSync(directory) === directory && lstatSync(directory).isDirectory());
  requireBuild(readdirSync(output).length === 0);
  requireBuild(JSON.stringify(readdirSync(source).sort()) === JSON.stringify(['package.json', 'src']));
  requireBuild(realpathSync(join(source, 'src')) === join(source, 'src'));
  requireBuild(JSON.stringify(readdirSync(join(source, 'src')).sort()) === JSON.stringify(FILES.slice(1).map(name => name.slice(4)).sort()));
  const startedAt = new Date().toISOString(), start = process.hrtime.bigint();
  const inputs = new Map(FILES.map(name => [name, read(join(source, name))]));
  const descriptor = JSON.parse(inputs.get('package.json'));
  requireBuild(descriptor.type === 'module' && descriptor.engines.node === '24.22.1'
    && descriptor.dependencies === undefined);
  for (const name of MODULES) {
    const checked = spawnSync(process.execPath, ['--check', join(source, 'src', name + '.js')],
      { encoding:'utf8', timeout:10000, maxBuffer:65536 });
    if (checked.status !== 0) {
      // This compiler output goes only to the separately retained private operation log.
      if (checked.stderr) process.stderr.write(checked.stderr);
      throw new Error('LOG_NODE_PACKAGE_INVALID');
    }
  }
  mkdirSync(join(output, 'sdk'), { mode:0o700 }); mkdirSync(join(output, 'sdk/src'), { mode:0o700 });
  const files = [];
  for (const [name, data] of inputs) {
    requireBuild(read(join(source, name)).equals(data));
    const target = join(output, 'sdk', name);
    writeFileSync(target, data, { mode:0o600, flag:'wx' });
    const packaged = read(target);
    requireBuild(packaged.equals(data));
    files.push({ path:name, sourceSha256:sha(data), distributionSha256:sha(packaged), bytes:packaged.length });
  }
  const record = { schemaVersion:1, node:process.version.slice(1), v8:process.versions.v8,
    startedAt, finishedAt:new Date().toISOString(), durationMs:Number((process.hrtime.bigint() - start) / 1000000n),
    filesChecked:MODULES.length, files };
  writeFileSync(join(output, 'package-record.json'), JSON.stringify(record), { mode:0o600, flag:'wx' });
  return record;
}

export function main(args = process.argv.slice(2)) {
  try {
    requireBuild(args.length === 4 && args[0] === '--source' && args[2] === '--output');
    packageDistribution(args[1], args[3]);
    console.log('{"result":"PACKAGED","filesChecked":7}'); return 0;
  } catch { console.log('{"result":"FAIL","code":"LOG_NODE_PACKAGE_INVALID"}'); return 1; }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = main();
