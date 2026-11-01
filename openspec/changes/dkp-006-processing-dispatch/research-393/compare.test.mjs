import {evaluate,summarize} from './compare.mjs';
import {createHash} from 'node:crypto';
import {readFileSync,mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';
import test from 'node:test';
const hash=s=>createHash('sha256').update(s).digest('hex');
const good={id:'object',json:'{}',expected:'CANONICAL',canonical:'{}',sha256:hash('{}')};
const bad={id:'bad',utf8Bytes:[255],expected:'REJECT'};
test('preserves candidate failures and never promotes value-only reference to raw parser evidence',()=>{
 const rows=evaluate([good,bad],'object\te30=\nbad\te30=\n',JSON.stringify);
 assert.deepEqual(rows.map(r=>r.java),['PASS','FAIL']);assert.equal(rows[1].actual,'{}');
 assert.deepEqual(summarize(rows),{total:2,passed:1,failed:['bad'],javascriptValuePositives:1,javascriptRawNegatives:'NOT_PROVEN_BY_VALUE_REFERENCE'});
 assert.equal(evaluate([bad],'bad\tREJECT\n',JSON.stringify)[0].actual,'REJECT');
 for(const output of ['', 'object\te30=\nobject\te30=\n','unknown\te30=\n','object\te30=\textra\n'])assert.throws(()=>evaluate([good],output,JSON.stringify));
});
test('full immutable shared corpus plus extension and CLI are observation adapters, not candidate proof',()=>{
 const shared=JSON.parse(readFileSync(new URL('../research-379/conformance-vectors.json',import.meta.url))).vectors;
 const extra=JSON.parse(readFileSync(new URL('./additional-vectors.json',import.meta.url))).vectors;const vectors=[...shared,...extra];
 assert.equal(shared.length,45);assert.equal(extra.length,38);assert.equal(new Set(vectors.map(v=>v.id)).size,83);
 assert.equal(vectors.filter(v=>v.expected==='REJECT').length,41);
 for(const v of vectors.filter(v=>v.expected==='CANONICAL'))assert.equal(hash(v.canonical),v.sha256);
 const dir=mkdtempSync(join(tmpdir(),'dkp393-compare-'));try{
  const cli=fileURLToPath(new URL('./compare.mjs',import.meta.url));
  assert.equal(spawnSync(process.execPath,[cli,'prepare',dir]).status,0);
  assert.equal(readFileSync(join(dir,'input.tsv'),'utf8').trim().split('\n').length,83);
  writeFileSync(join(dir,'reference.mjs'),'export default JSON.stringify;\n');
  const observations=vectors.map(v=>`${v.id}\t${v.expected==='REJECT'?'REJECT':Buffer.from(v.canonical).toString('base64')}`).join('\n')+'\n';
  for(const mode of ['TITANIUM_STRICT','ERDTMAN_STRICT','GRAALJS_STRICT'])writeFileSync(join(dir,mode+'.tsv'),observations);
  assert.equal(spawnSync(process.execPath,[cli,'assess',dir]).status,0);
  const result=JSON.parse(readFileSync(join(dir,'observed-results.json')));assert.equal(result.status,'RESEARCH_ONLY_NO_RUNTIME_SELECTION');
  assert.equal(result.modes.TITANIUM_STRICT.shared.passed,45);
  assert.ok(result.modes.TITANIUM_STRICT.shared.javascriptValuePositives<31,'Wrong reference bytes must fail independently');
  assert.notEqual(spawnSync(process.execPath,[cli,'unknown',dir],{stdio:'ignore'}).status,0);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
