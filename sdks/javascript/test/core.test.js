import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWire, encodeBatch, isPrepared, bytes, uuid, EVENT_CODES, LIMITS } from '../src/wire.js';
import { LogQueue } from '../src/queue.js';

const id = n => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const execution = { buildId:id(1), runId:id(2), instanceId:id(3), component:'admin-portal', gitSha:'a'.repeat(40), sourceFingerprint:'b'.repeat(64) };
const catalog = () => ({ events:[...EVENT_CODES], phases:['STARTUP','SHUTDOWN'] });
const input = (n = 10) => ({ eventId:id(n), timestamp:'2026-11-03T03:00:00.000Z', severity:'DEBUG', eventName:'process.started', correlationId:id(4), phase:'STARTUP', outcome:'STARTED', durationMs:0, attempt:0, count:1, cleanupSettled:false });
const wire = () => createWire(execution, catalog());
const rejected = fn => assert.throws(fn, { name:'TypeError', message:'INVALID_LOG_EVENT' });

test('preserves original execution and typed values, isolates mutations and excludes arbitrary authority/text', () => {
  const identity = {...execution}, vocabulary = catalog();
  const client = createWire(identity, vocabulary);
  identity.buildId=id(100); vocabulary.phases.push('raw-secret');
  const original = input(), record=client.prepare(original); original.count=100;
  assert.equal(record.event.buildId, execution.buildId);
  assert.equal(record.event.context.attributes.count, 1);
  assert.deepEqual(JSON.parse(record.text),record.event);
  assert.equal(record.size,bytes(record.text)); assert.ok(isPrepared(record));
  for (const value of [record, record.event, record.event.context, record.event.context.attributes, client.execution]) assert.ok(Object.isFrozen(value));
  rejected(() => client.prepare({...input(),phase:'raw-secret'}));
  for(const field of ['message','tenant','subject','csrfToken','password','attributes','stack']) rejected(() => client.prepare({...input(),[field]:'sensitive-string'}));
  assert.ok(!record.text.includes('sensitive-string'));
  assert.equal(isPrepared({id:record.id,text:record.text}),false); assert.equal(isPrepared(null),false); assert.equal(isPrepared('text'),false);
  const traced=client.prepare({...input(),traceId:'1'.repeat(32),spanId:'2'.repeat(16)});
  assert.equal(traced.event.traceId,'1'.repeat(32));assert.equal(traced.event.spanId,'2'.repeat(16));
  const traceOnly=client.prepare({...input(),traceId:'1'.repeat(32)});assert.ok(!Object.hasOwn(traceOnly.event,'spanId'));
});

test('rejects malformed execution and vocabulary without leaking values', () => {
  const invalid=[null,[],{}, {...execution,extra:true}, {...execution,buildId:id(2)}, {...execution,runId:'00000000-0000-0000-0000-000000000000'}, {...execution,instanceId:'bad'}, {...execution,component:'bad component'}, {...execution,component:1}, {...execution,gitSha:'x'}, {...execution,sourceFingerprint:'x'}];
  for(const value of invalid)rejected(() => createWire(value,catalog()));
  for(const value of [{events:[],phases:['A']},{events:['unknown'],phases:['A']},{events:[EVENT_CODES[0],EVENT_CODES[0]],phases:['A']},{events:'text',phases:['A']},{events:[EVENT_CODES[0]],phases:[]},{events:[EVENT_CODES[0]],phases:['A','A']},{events:[EVENT_CODES[0]],phases:['raw secret']},{events:[EVENT_CODES[0]],phases:[1]},{events:[EVENT_CODES[0]],phases:'text'},{...catalog(),extra:1}])rejected(() => createWire(execution,value));
  assert.equal(uuid(null),false);assert.equal(uuid('BAD'),false);assert.equal(uuid(id(1)),true);
});

test('rejects invalid typed payloads, unknown codes/phases, unsafe numbers and trace pairs', () => {
  const client=wire();
  for(const patch of [{eventId:'bad'},{correlationId:'bad'},{timestamp:'invalid'},{timestamp:'2026-02-31T03:00:00Z'},{timestamp:1},{severity:'SECRET'},{eventName:'console raw text'},{phase:'UNKNOWN'},{outcome:'WHAT'},{durationMs:-1},{attempt:0.2},{count:Number.MAX_SAFE_INTEGER+1},{count:Infinity},{cleanupSettled:'true'},{traceId:'0'.repeat(32)},{traceId:'bad'},{spanId:'1'.repeat(16)},{traceId:'1'.repeat(32),spanId:'0'.repeat(16)},{traceId:'1'.repeat(32),spanId:'bad'}])rejected(() => client.prepare({...input(),...patch}));
  rejected(()=>client.prepare({}));rejected(()=>client.prepare(null));
  const max=client.prepare({...input(),durationMs:Number.MAX_SAFE_INTEGER,attempt:3,count:5,cleanupSettled:true});assert.equal(max.event.context.durationMs,Number.MAX_SAFE_INTEGER);
});

test('batch wire is bounded, byte accurate and accepts only validated immutable records', () => {
  const client=wire(), events=Array.from({length:32},(_,n)=>client.prepare(input(n+10)));
  const batch=encodeBatch(events);assert.ok(bytes(batch)<=LIMITS.batchBytes);assert.equal(JSON.parse(batch).events.length,32);
  for(const value of [null,[],[...events,client.prepare(input(99))],[events[0],events[0]],[{id:id(1),text:'{"password":"secret"}'}]])rejected(()=>encodeBatch(value));
  assert.equal(bytes('é🙂'),6);
});

test('admission is in-memory, finite, idempotent and exposes rejection/overflow/close', () => {
  const queue=new LogQueue(), client=wire(), event=client.prepare(input());
  assert.equal(queue.admit(event,'epoch-one'),'ACCEPTED');assert.equal(queue.admit(event,'epoch-one'),'DUPLICATE');
  assert.equal(queue.admit(event,'epoch-two'),'REJECTED');
  assert.equal(queue.admit(client.prepare({...input(),count:2}),'epoch-one'),'REJECTED');
  for(const capture of ['', 'x'.repeat(129), null])assert.equal(queue.admit(event,capture),'REJECTED');
  assert.equal(queue.admit({id:id(1),text:'secret'},'epoch-one'),'REJECTED');
  for(let n=1;n<LIMITS.queueEvents;n++)assert.equal(queue.admit(client.prepare(input(n+20)),'epoch-one'),'ACCEPTED');
  assert.equal(queue.admit(client.prepare(input(99999)),'epoch-one'),'DROPPED');
  const status=queue.status();assert.equal(status.pending,LIMITS.queueEvents);assert.ok(status.pendingBytes<=LIMITS.queueBytes);assert.equal(status.dropped,1);assert.equal(status.committed,0);assert.equal(status.rejected,6);assert.ok(Object.isFrozen(status));
  queue.close();queue.close();assert.equal(queue.admit(event,'epoch-one'),'CLOSED');assert.equal(queue.status().pending,LIMITS.queueEvents);assert.equal(queue.status().closed,true);
});

test('batch construction preserves capture, event IDs and full-envelope byte bounds', () => {
  const queue=new LogQueue(), client=wire();
  for(let n=10;n<50;n++)queue.admit(client.prepare(input(n)),n===11?'other':'epoch');
  assert.equal(queue.batch('epoch').length,32);assert.equal(queue.batch('other').length,1);assert.equal(queue.batch('absent').length,0);
  const encode=events=>JSON.stringify({csrfToken:'x'.repeat(512),contextEpoch:id(9),captureClass:'CURRENT_CONTEXT',batch:JSON.parse(encodeBatch(events)),padding:'é'.repeat(12000)});
  const selected=queue.batch('epoch',encode);assert.ok(selected.length>0 && selected.length<32);assert.ok(bytes(encode(selected))<=LIMITS.batchBytes);
  assert.equal(queue.batch('epoch',()=>{throw Error('private fixture exception');}).length,0);
  assert.equal(queue.batch('epoch',()=> 'x'.repeat(LIMITS.batchBytes+1)).length,0);
  rejected(()=>queue.batch('epoch',()=>null));
});

test('submission never means commit and retirement preserves unknown persistence', () => {
  const queue=new LogQueue(), client=wire();
  for(let n=10;n<14;n++)queue.admit(client.prepare(input(n)),n===13?'new':'old');
  queue.submitted([id(10),id(11),id(999)]);assert.equal(queue.status().unknown,2);assert.equal(queue.status().committed,0);
  queue.committed([id(10)],'wrong');assert.equal(queue.status().committed,0);
  queue.committed([id(10),id(999)],'old');assert.equal(queue.status().committed,1);assert.equal(queue.status().unknown,1);
  queue.retire('old');const status=queue.status();assert.equal(status.pending,1);assert.equal(status.unknown,0);assert.equal(status.discardedUnknown,1);assert.equal(status.dropped,1);assert.equal(status.committed,1);
  assert.equal(queue.batch('new')[0].id,id(13));assert.equal(queue.batch('old').length,0);queue.retire('old');
  queue.committed([id(13)],'new');assert.equal(queue.status().pendingBytes,0);assert.equal(queue.status().committed,2);
});

// Host objects may have accessors; validation must apply to the exact captured value.
test('changing accessors cannot replace validated diagnostic fields with raw data', () => {
  let reads=0;const descriptor=input();Object.defineProperty(descriptor,'phase',{enumerable:true,get(){return reads++===0?'STARTUP':'password-secret';}});
  const event=wire().prepare(descriptor);assert.equal(reads,1);assert.equal(event.event.context.phase,'STARTUP');assert.ok(!event.text.includes('password-secret'));
  let identities=0;const identity={...execution};Object.defineProperty(identity,'component',{enumerable:true,get(){return identities++===0?'admin-portal':'password-secret';}});
  const client=createWire(identity,catalog());assert.equal(identities,1);assert.equal(client.execution.component,'admin-portal');
});

test('permanent response rejection retires only matching capture and preserves submitted uncertainty', () => {
  const queue=new LogQueue(),client=wire();queue.admit(client.prepare(input(10)),'old');queue.admit(client.prepare(input(11)),'old');queue.admit(client.prepare(input(12)),'new');queue.submitted([id(10)]);
  queue.reject([id(10)],'wrong');assert.equal(queue.status().rejected,0);queue.reject([id(10),id(11),id(999)],'old');
  assert.equal(queue.status().rejected,2);assert.equal(queue.status().discardedUnknown,1);assert.equal(queue.status().committed,0);assert.equal(queue.status().dropped,0);assert.equal(queue.status().pending,1);assert.equal(queue.batch('new')[0].id,id(12));
});
