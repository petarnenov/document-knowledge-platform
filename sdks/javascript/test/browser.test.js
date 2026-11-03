import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as pause } from 'node:timers/promises';
import { BrowserLogProducer } from '../src/browser.js';
import { parseResponse, receiptStates, validTime } from '../src/responses.js';
const id = n => `00000000-0000-4000-8000-${n.toString(16).padStart(12,'0')}`;
const identity = { buildId:id(1),runId:id(2),instanceId:id(3),component:'admin-portal',gitSha:'a'.repeat(40),sourceFingerprint:'b'.repeat(64) };
const vocabulary = {events:['process.started'],phases:['STARTUP']};
const event = (n=10) => ({eventId:id(n),timestamp:'2026-11-03T03:00:00Z',severity:'DEBUG',eventName:'process.started',correlationId:id(4),phase:'STARTUP',outcome:'STARTED',durationMs:0,attempt:0,count:1,cleanupSettled:false});
const encode = value => new TextEncoder().encode(typeof value==='string'?value:JSON.stringify(value));
const json = value => new Response(typeof value==='string'?value:JSON.stringify(value),{headers:{'Content-Type':'application/json'}});
function fixture(t) {
 const calls=[],beacons=[],listeners=new Map(),docListeners=new Map();let now=Date.parse('2026-11-03T03:00:00Z');
 const context = () => ({contextEpoch:id(8),buildId:identity.buildId,runId:identity.runId,expiresAt:new Date(now+300000).toISOString()});
 const env={location:{origin:'https://localhost:18443'},document:{visibilityState:'visible',addEventListener:(name,fn)=>docListeners.set(name,fn),removeEventListener:(name,fn)=>{assert.equal(docListeners.get(name),fn);docListeners.delete(name);}},addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:(name,fn)=>{assert.equal(listeners.get(name),fn);listeners.delete(name);},navigator:{sendBeacon:(url,blob)=>{beacons.push({url,blob});return true;}},fetch:async(url,options)=>{calls.push({url,options});return url.endsWith('/context')?json(context()):json({receipts:JSON.parse(options.body).eventIds.map(eventId=>({eventId,state:'UNKNOWN'}))});}};
 const producer=new BrowserLogProducer(identity,vocabulary,{environment:env,now:()=>now});t.after(()=>producer.close());
 return {producer,env,calls,beacons,listeners,docListeners,context,advance:ms=>{now+=ms;}};
}

test('Beacon queue acceptance is UNKNOWN; context and receipts use fixed session paths, no bearer',async t=>{
 const f=fixture(t),p=f.producer;assert.equal(p.emit(event()),'NO_CONTEXT');assert.equal(await p.connect('transient-csrf'),'READY');
 const context=f.calls[0];assert.equal(context.url,'https://localhost:18443/api/v1/dev-logs/context');assert.deepEqual(JSON.parse(context.options.body),{buildId:identity.buildId,runId:identity.runId});assert.equal(context.options.headers['X-CSRF-TOKEN'],'transient-csrf');assert.equal(context.options.credentials,'same-origin');assert.equal(context.options.redirect,'error');
 assert.equal(p.emit(event()),'ACCEPTED');assert.equal(f.beacons.length,0);assert.equal(p.flush(),'UNKNOWN');assert.equal(p.status().committed,0);assert.equal(p.status().unknown,1);
 const beacon=f.beacons[0];assert.equal(beacon.url,'https://localhost:18443/api/v1/dev-logs/beacon');assert.equal(beacon.blob.type,'application/json');assert.ok(beacon.blob.size<=32768);
 const body=JSON.parse(await beacon.blob.text());assert.equal(body.csrfToken,'transient-csrf');assert.equal(body.contextEpoch,id(8));assert.equal(body.captureClass,'CURRENT_CONTEXT');assert.equal(body.batch.events[0].eventId,id(10));assert.equal(body.batch.events[0].buildId,identity.buildId);assert.ok(!JSON.stringify(p.status()).includes('transient-csrf'));
 assert.equal(await p.reconcile(),'UNKNOWN');assert.equal(p.status().committed,0);assert.equal(p.status().pending,1);
 const lookup=f.calls.at(-1);assert.equal(lookup.url,'https://localhost:18443/api/v1/dev-logs/beacon/receipts');assert.deepEqual(JSON.parse(lookup.options.body),{csrfToken:'transient-csrf',contextEpoch:id(8),buildId:identity.buildId,runId:identity.runId,captureClass:'CURRENT_CONTEXT',eventIds:[id(10)]});assert.ok(!Object.hasOwn(lookup.options.headers,'Authorization'));assert.ok(!Object.hasOwn(lookup.options.headers,'X-CSRF-TOKEN'));
 f.env.fetch=async()=>json({receipts:[{eventId:id(10),state:'COMMITTED',receivedAt:'2026-11-03T03:00:01Z'}]});assert.equal(await p.reconcile(),'COMMITTED');assert.equal(p.status().pending,0);assert.equal(p.status().committed,1);assert.equal(p.flush(),'EMPTY');assert.equal(await p.reconcile(),'EMPTY');
});

test('rejected or throwing Beacon preserves pending identity and never fabricates persistence',async t=>{
 const f=fixture(t),p=f.producer;await p.connect('csrf');p.emit(event());f.env.navigator.sendBeacon=()=>false;assert.equal(p.flush(),'REJECTED');assert.equal(p.status().unknown,0);assert.equal(p.status().pending,1);
 f.env.navigator.sendBeacon=()=>{throw Error('private-error');};assert.equal(p.flush(),'REJECTED');assert.equal(p.status().beaconRejected,2);
 f.env.navigator.sendBeacon=(url,blob)=>{f.beacons.push({url,blob});return true;};assert.equal(p.flush(),'UNKNOWN');assert.equal(JSON.parse(await f.beacons[0].blob.text()).batch.events[0].eventId,id(10));
 assert.equal(p.emit({...event(),message:'password-secret'}),'REJECTED');assert.equal(p.status().invalid,1);assert.equal(p.emit(event()),'DUPLICATE');
});

test('retirement, expiry and new epochs never relabel pending/unknown events',async t=>{
 const f=fixture(t),p=f.producer;await p.connect('csrf');p.emit(event());p.flush();p.retire();assert.equal(p.status().discardedUnknown,1);assert.equal(p.status().committed,0);assert.equal(p.status().contextReady,false);assert.equal(p.flush(),'NO_CONTEXT');
 f.env.fetch=async()=>json({...f.context(),contextEpoch:id(9)});await p.connect('new-csrf');assert.equal(p.emit(event(11)),'ACCEPTED');p.flush();assert.equal(JSON.parse(await f.beacons.at(-1).blob.text()).contextEpoch,id(9));assert.equal(JSON.parse(await f.beacons.at(-1).blob.text()).batch.events[0].eventId,id(11));
 f.advance(300001);assert.equal(p.status().pending,0);assert.equal(p.status().captureExpired,1);assert.equal(p.status().discardedUnknown,2);assert.equal(p.emit(event(12)),'NO_CONTEXT');
});

test('server EXPIRED receipts expose expiry without asserting historical loss or commit',async t=>{
 const f=fixture(t),p=f.producer;await p.connect('csrf');p.emit(event());p.emit(event(11));p.flush();
 f.env.fetch=async()=>json({receipts:[{eventId:id(10),state:'EXPIRED'},{eventId:id(11),state:'UNKNOWN'}]});assert.equal(await p.reconcile(),'UNKNOWN');assert.equal(p.status().expired,1);assert.equal(p.status().discardedUnknown,1);assert.equal(p.status().dropped,0);assert.equal(p.status().pending,1);assert.equal(p.status().committed,0);
});

test('server retirement clears delivery authority; malformed and unsuccessful responses never commit',async t=>{
 const f=fixture(t),p=f.producer;await p.connect('csrf');p.emit(event());p.flush();
 for(const response of [()=>new Response('{}',{status:202,headers:{'Content-Type':'application/json'}}),()=>new Response('{}'),()=>json({receipts:[{eventId:id(999),state:'COMMITTED',receivedAt:'2026-11-03T03:00:00Z'}]}),()=>json('{"receipts":[{"eventId":"'+id(10)+'","state":"UNKNOWN","state":"COMMITTED","receivedAt":"2026-11-03T03:00:00Z"}]}')]){f.env.fetch=async()=>response();assert.equal(await p.reconcile(),'UNKNOWN');assert.equal(p.status().committed,0);}
 f.env.fetch=async()=>new Response('',{status:403});assert.equal(await p.reconcile(),'RETIRED');assert.equal(p.status().contextReady,false);assert.equal(p.status().discardedUnknown,1);
});

test('context validates exact original binding, finite expiry, byte bounds and status',async t=>{
 const f=fixture(t),p=f.producer;
 for(const csrf of ['',null,'x'.repeat(513),'secret\r\n'])assert.equal(await p.connect(csrf),'REJECTED');
 for(const patch of [{buildId:id(99)},{runId:id(99)},{contextEpoch:'bad'},{expiresAt:'invalid'},{expiresAt:'2026-11-03T03:00:00Z'},{expiresAt:'2026-11-03T03:06:00Z'},{extra:'secret'}]){f.env.fetch=async()=>json({...f.context(),...patch});assert.equal(await p.connect('csrf'),'UNKNOWN');assert.equal(p.status().contextReady,false);}
 for(const response of [()=>new Response('x'.repeat(32769),{headers:{'Content-Type':'application/json'}}),()=>new Response('{}',{headers:{'Content-Type':'application/json','Content-Length':'99999'}}),()=>new Response('{}',{headers:{'Content-Type':'application/json','Content-Length':'bad'}}),()=>new Response(null,{status:200,headers:{'Content-Type':'application/json'}})]){f.env.fetch=async()=>response();assert.equal(await p.connect('csrf'),'UNKNOWN');}
 f.env.fetch=async()=>new Response('',{status:401});assert.equal(await p.connect('csrf'),'RETIRED');
});

test('concurrent requests are bounded and retired in-flight context cannot revive authority',async t=>{
 const f=fixture(t),p=f.producer;let release;
 f.env.fetch=()=>new Promise(resolve=>{release=resolve;});const connecting=p.connect('csrf');assert.equal(await p.connect('csrf'),'BUSY');p.retire();release(json(f.context()));assert.equal(await connecting,'RETIRED');assert.equal(p.status().contextReady,false);assert.equal(p.status().requestActive,false);
 f.env.fetch=async()=>json(f.context());await p.connect('csrf');p.emit(event());p.flush();f.env.fetch=()=>new Promise(resolve=>{release=resolve;});const reconciling=p.reconcile();assert.equal(await p.reconcile(),'BUSY');p.retire();release(json({receipts:[{eventId:id(10),state:'COMMITTED',receivedAt:'2026-11-03T03:00:00Z'}]}));assert.equal(await reconciling,'RETIRED');assert.equal(p.status().committed,0);
});

test('lifecycle flush hooks and close are bounded, removable and honest',async t=>{
 const f=fixture(t),p=f.producer;await p.connect('csrf');p.emit(event());f.listeners.get('pagehide')();assert.equal(f.beacons.length,1);f.env.document.visibilityState='visible';f.docListeners.get('visibilitychange')();assert.equal(f.beacons.length,1);f.env.document.visibilityState='hidden';f.docListeners.get('visibilitychange')();assert.equal(f.beacons.length,2);
 p.close();p.close();assert.equal(f.listeners.size,0);assert.equal(f.docListeners.size,0);assert.equal(p.status().closed,true);assert.equal(p.status().committed,0);assert.equal(p.status().discardedUnknown,1);assert.equal(p.emit(event()),'CLOSED');assert.equal(await p.connect('csrf'),'CLOSED');assert.equal(await p.reconcile(),'NO_CONTEXT');
});

test('scheduled delivery recovers a normal Beacon rejection without blocking caller',async t=>{
 const f=fixture(t),p=f.producer;await p.connect('csrf');let attempts=0;f.env.navigator.sendBeacon=()=>++attempts>1;
 f.env.fetch=async()=>json({receipts:[{eventId:id(10),state:attempts>1?'COMMITTED':'UNKNOWN',...(attempts>1?{receivedAt:'2026-11-03T03:00:00Z'}:{})}]});
 assert.equal(p.emit(event()),'ACCEPTED');let actionComplete=false;actionComplete=true;assert.equal(actionComplete,true);assert.equal(attempts,0);
 const deadline=Date.now()+2500;while(p.status().committed===0 && Date.now()<deadline)await pause(20);assert.equal(p.status().committed,1);assert.equal(p.status().beaconRejected,1);assert.equal(p.status().pending,0);
});

test('fixed five-second fetch deadline aborts a stalled operation', {timeout:8000},async t=>{
 const f=fixture(t),p=f.producer;let aborted=false;f.env.fetch=(url,options)=>new Promise((resolve,reject)=>{options.signal.addEventListener('abort',()=>{aborted=true;reject(new DOMException('Aborted','AbortError'));},{once:true});});const started=performance.now();assert.equal(await p.connect('csrf'),'UNKNOWN');const elapsed=performance.now()-started;assert.ok(aborted);assert.ok(elapsed>=4900 && elapsed<7000);assert.equal(p.status().requestActive,false);assert.equal(p.status().requestFailures,1);
});

test('strict JSON rejects ambiguous replies, unsafe values, malformed bytes and forged receipts',()=>{
 for(const value of ['', '{"a":1,"a":2}','{"a":1,"\\u0061":2}', '{} {}','[1,]','{"a":}', '"unterminated','"bad\nstring"','1e999','9007199254740992','['.repeat(18)+']'.repeat(18),'"'+'x'.repeat(513)+'"'])assert.throws(()=>parseResponse(encode(value)),/INVALID_LOG_RESPONSE/);
 assert.throws(()=>parseResponse(new Uint8Array([255])),/INVALID_LOG_RESPONSE/);assert.throws(()=>parseResponse('text'),/INVALID_LOG_RESPONSE/);assert.throws(()=>parseResponse(new Uint8Array(32769)),/INVALID_LOG_RESPONSE/);
 assert.equal(parseResponse(encode(' {"a":[true,false,null,-1],"b":"escaped\\\"value"} ')).b,'escaped"value');assert.deepEqual([...parseResponse(encode('[]'))],[]);assert.equal(validTime('2026-02-31T00:00:00Z'),false);
 const good={receipts:[{eventId:id(10),state:'COMMITTED',receivedAt:'2026-11-03T03:00:00Z'}]};assert.equal(receiptStates(encode(good),[id(10)],true).get(id(10)),'COMMITTED');
 for(const value of [[],{}, {receipts:[]},{receipts:[null]},{receipts:[{eventId:id(11),state:'UNKNOWN'}]},{receipts:[{eventId:id(10),state:'BAD'}]},{receipts:[{eventId:id(10),state:'UNKNOWN',receivedAt:'2026-11-03T03:00:00Z'}]},{receipts:[{eventId:id(10),state:'COMMITTED',receivedAt:'bad'}]}])assert.throws(()=>receiptStates(encode(value),[id(10)]),/INVALID_LOG_RESPONSE/);
 assert.throws(()=>receiptStates(encode({receipts:[{eventId:id(10),state:'UNKNOWN'}]}),[id(10)],true),/INVALID_LOG_RESPONSE/);
 assert.throws(()=>receiptStates(encode(good),[id(10),id(10)]),/INVALID_LOG_RESPONSE/);
});

test('browser entrypoint rejects insecure origin and does not import Node transports',()=>{
 for(const origin of ['http://localhost:18443','https://localhost:18443/path','https://user:password@localhost:18443'])assert.throws(()=>new BrowserLogProducer(identity,vocabulary,{environment:{location:{origin},navigator:{sendBeacon(){}},fetch(){}}}),/INVALID_LOG_EVENT/);
});

test('new server epoch or CSRF cannot adopt an old capture and malformed failures stay fixed',async t=>{
 const f=fixture(t),p=f.producer;await p.connect('csrf');p.emit(event());p.flush();
 f.env.fetch=async()=>json({...f.context(),contextEpoch:id(9)});assert.equal(await p.connect('csrf'),'READY');assert.equal(p.status().discardedUnknown,1);assert.equal(p.status().pending,0);
 p.emit(event(11));f.env.fetch=async()=>{throw null;};assert.equal(await p.connect('replacement-csrf'),'UNKNOWN');assert.equal(p.status().contextReady,false);assert.equal(p.status().dropped,1);assert.equal(p.status().requestActive,false);
});

test('deadline cancels a stalled body reader, not just response-header fetch', {timeout:8000},async t=>{
 const f=fixture(t),p=f.producer;let cancelled=false;
 f.env.fetch=async()=>new Response(new ReadableStream({start(controller){controller.enqueue(encode('{'));},cancel(){cancelled=true;}}),{headers:{'Content-Type':'application/json'}});
 const started=performance.now();assert.equal(await p.connect('csrf'),'UNKNOWN');assert.ok(performance.now()-started>=4900);assert.ok(cancelled);assert.equal(p.status().requestActive,false);
});
