import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
function imports(source) { return [...source.matchAll(/(?:from\s*|import\s*\()\s*['"]([^'"]+)['"]/g)].map(match=>match[1]); }
function allowed(source) { for(const path of imports(source))assert.ok(path.startsWith('./') && path.endsWith('.js'),'Browser dependency must be a local SDK module'); }
test('browser dependency graph stays independent of Node, service and framework modules',async()=>{
 const visited=new Set();async function visit(url){if(visited.has(url.href))return;visited.add(url.href);const source=await readFile(url,'utf8');allowed(source);for(const path of imports(source))await visit(new URL(path,url));}
 await visit(new URL('../src/browser.js',import.meta.url));assert.equal(visited.size,4);
 for(const source of ["import fs from 'node:fs';","import { x } from 'service-runtime';","const fs=await import('node:fs');"])assert.throws(()=>allowed(source),/Browser dependency/);
 const pkg=JSON.parse(await readFile(new URL('../package.json',import.meta.url),'utf8'));assert.equal(pkg.exports['./browser'].import,'./src/browser.js');assert.ok(fileURLToPath(new URL('../src/browser.js',import.meta.url)).endsWith('/src/browser.js'));
});

test('Node entrypoint is explicit and does not depend on browser/session implementations',async()=>{
 const visited=new Set();async function visit(url){if(visited.has(url.href))return;visited.add(url.href);const source=await readFile(url,'utf8');for(const path of imports(source)){if(path.startsWith('node:')){assert.ok(['node:https','node:crypto'].includes(path));continue;}assert.ok(path.startsWith('./'));assert.ok(!path.includes('browser'));await visit(new URL(path,url));}}
 await visit(new URL('../src/node.js',import.meta.url));assert.equal(visited.size,5);
 const pkg=JSON.parse(await readFile(new URL('../package.json',import.meta.url),'utf8'));assert.equal(pkg.exports['./node'].import,'./src/node.js');assert.equal(pkg.exports['./node'].types,'./src/node.d.ts');
});
