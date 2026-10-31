import { resolve } from 'node:path';
import { root, run, filesUnder, validateContract } from './lib.mjs';

for (const kind of ['openapi', 'asyncapi']) {
  const files = (await filesUnder(resolve(root, 'contracts', kind))).filter((file) => /\.(yaml|yml|json)$/.test(file));
  if (kind === 'openapi' && files.length === 0) throw new Error('The implemented operational contract is missing.');
  for (const file of files) await validateContract(file, kind);
  console.log(`${kind}: ${files.length} published contract(s) validated${files.length === 0 ? '; no contracts implemented' : ''}.`);
}
console.log(run(process.execPath, [
  '--experimental-test-coverage', '--test-coverage-include=tools/contracts/null-model.mjs',
  '--test-coverage-lines=75', '--test-coverage-branches=75', '--test-coverage-functions=75',
  '--test', 'tools/contracts/validate.test.mjs', 'tools/contracts/null-model.test.mjs',
]));
console.log(run(process.execPath, ['tools/contracts/drift.mjs']));
console.log(run(resolve(root, 'node_modules/.bin/tsc'), ['--project', 'generated-clients/typescript/tsconfig.json']));
console.log(run('./gradlew', [':generated-clients:java:compileJava', '--no-daemon']));
console.log('Contract validation, negative fixtures, SDK drift and Java/TypeScript compilation passed.');
