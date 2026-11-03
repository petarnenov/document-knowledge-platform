import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import { mkdtempSync, readFileSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { NodeHttpsTransport } from '../src/node-https.js';
import { NodeLogProducer } from '../src/node.js';
const id=n=>`00000000-0000-4000-8000-${n.toString(16).padStart(12,'0')}`;
const identity={buildId:id(1),runId:id(2),instanceId:id(3),component:'runtime-tools',gitSha:'a'.repeat(40),sourceFingerprint:'b'.repeat(64)};
const vocabulary={events:['process.started'],phases:['STARTUP']};
const event=(n=10)=>({eventId:id(n),timestamp:'2026-11-03T03:00:00Z',severity:'DEBUG',eventName:'process.started',correlationId:id(4),phase:'STARTUP',outcome:'STARTED',durationMs:0,attempt:0,count:1,cleanupSettled:false});
const token=randomUUID().replaceAll('-','')+randomUUID().replaceAll('-',''),secret=randomUUID();
const encode=value=>new TextEncoder().encode(JSON.stringify(value));
const commit=ids=>({receipts:ids.map(eventId=>({eventId,state:'COMMITTED',receivedAt:'2026-11-03T03:00:01Z'}))});
let directory,pfx,ca,wrongCa;
before(()=>{
 directory=mkdtempSync(join(tmpdir(),'dkp-node-sdk-test-'));chmodSync(directory,0o700);
 for(const name of ['server','other']){
  const file=join(directory,name+'.p12');execFileSync(process.env.DKP_TEST_KEYTOOL??'keytool',['-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048','-dname','CN=localhost','-ext','SAN=dns:localhost','-ext','BC=ca:true','-storetype','PKCS12','-keystore',file,'-storepass',secret,'-keypass',secret,'-validity','1','-noprompt'],{stdio:'ignore',timeout:20000});chmodSync(file,0o600);
  const cert=join(directory,name+'.crt');execFileSync(process.env.DKP_TEST_KEYTOOL??'keytool',['-exportcert','-rfc','-alias','fixture','-keystore',file,'-storepass',secret,'-file',cert],{stdio:'ignore',timeout:10000});chmodSync(cert,0o600);
 }
 pfx=readFileSync(join(directory,'server.p12'));ca=readFileSync(join(directory,'server.crt'));wrongCa=readFileSync(join(directory,'other.crt'));
});
after(()=>{if(directory)rmSync(directory,{recursive:true,force:true});});
async function fixture(t,handler){
 const requests=[],sockets=new Set();const server=https.createServer({pfx,passphrase:secret},async(req,res)=>{
  const chunks=[];for await(const chunk of req)chunks.push(chunk);const raw=Buffer.concat(chunks);const body=JSON.parse(raw.toString());const row={path:req.url,headers:req.headers,raw:raw.toString(),body};requests.push(row);
  try{await handler(row,req,res);}catch{res.destroy();}
 });server.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});await new Promise(resolve=>server.listen(0,'localhost',resolve));
 t.after(async()=>{for(const socket of sockets)socket.destroy();await new Promise(resolve=>server.close(resolve));});
 const endpoint=`https://localhost:${server.address().port}/api/v1/dev-logs`;
 return {server,endpoint,requests,configuration:{endpoint,producerToken:token,ca},sockets};
}
function respond(res,value,status=200,headers={}){res.writeHead(status,{'Content-Type':'application/json',...headers});res.end(typeof value==='string'?value:JSON.stringify(value));}
async function until(condition,timeout=3500){const deadline=Date.now()+timeout;while(!condition()&&Date.now()<deadline)await pause(10);assert.ok(condition(),'Expected bounded asynchronous condition');}
function transport(t,configuration){const result=new NodeHttpsTransport(configuration);t.after(()=>result.close());return result;}
function producer(t,configuration){const result=new NodeLogProducer(identity,vocabulary,configuration);t.after(()=>{result.close();return result.whenClosed();});return result;}

test('real TLS uses only fixed paths and producer bearer, no proxy/redirect, bounded encoded response',async t=>{
 const f=await fixture(t,(row,req,res)=>respond(res,commit([id(10)])));const client=transport(t,f.configuration);
 const body=encode({schemaVersion:1,events:[]});const response=await client.post('batches',body);assert.equal(response.status,200);assert.deepEqual(JSON.parse(response.body),commit([id(10)]));
 assert.equal(f.requests[0].path,'/api/v1/dev-logs/batches');assert.equal(f.requests[0].headers.authorization,'Bearer '+token);assert.equal(f.requests[0].headers['content-type'],'application/json');assert.equal(f.requests[0].raw,new TextDecoder().decode(body));
 await client.post('receipts',encode({eventIds:[id(10)]}));assert.equal(f.requests[1].path,'/api/v1/dev-logs/receipts');assert.equal(client.credentialFingerprint(),createHash('sha256').update('dkp-log-producer-credential-v1:'+token).digest('hex'));
});

test('configuration rejects unsafe endpoints, malformed CA and invalid credentials with fixed refusal',async t=>{
 const f=await fixture(t,(row,req,res)=>respond(res,{}));
 for(const value of [null,undefined])assert.throws(()=>new NodeHttpsTransport(value),{name:'TypeError',message:'INVALID_LOG_TRANSPORT_CONFIG'});
 for(const patch of [{endpoint:'http://localhost/api/v1/dev-logs'},{endpoint:f.endpoint+'?secret=private'},{endpoint:f.endpoint+'#secret'},{endpoint:f.endpoint+'/'},{endpoint:f.endpoint.replace('https://','https://user:private@')},{producerToken:'bad'},{producerToken:null},{ca:''},{ca:new Uint8Array(16385)},{ca:'not-a-certificate'},{ca:null}])assert.throws(()=>new NodeHttpsTransport({...f.configuration,...patch}),{name:'TypeError',message:'INVALID_LOG_TRANSPORT_CONFIG'});
});

test('unknown CA and wrong certificate hostname fail actual TLS instead of relaxing verification',async t=>{
 const f=await fixture(t,(row,req,res)=>respond(res,{}));const badTrust=transport(t,{...f.configuration,ca:wrongCa});await assert.rejects(badTrust.post('batches',encode({a:1})),/LOG_HTTP_UNAVAILABLE/);
 const address=f.server.address();const literal=address.family==='IPv6'?'['+address.address+']':address.address;const badHost=transport(t,{...f.configuration,endpoint:`https://${literal}:${address.port}/api/v1/dev-logs`});await assert.rejects(badHost.post('batches',encode({a:1})),/LOG_HTTP_UNAVAILABLE/);assert.equal(f.requests.length,0);
});

test('only one HTTP request can own transport; invalid input and close never allocate extra work',async t=>{
 const f=await fixture(t,()=>{}),client=transport(t,f.configuration);
 for(const [route,body]of[['bad',encode({})],['batches',null],['batches',new Uint8Array()],['batches',new Uint8Array(32769)]])await assert.rejects(client.post(route,body),/LOG_HTTP_UNAVAILABLE/);
 const first=client.post('batches',encode({a:1}));const rejection=assert.rejects(first,/LOG_HTTP_UNAVAILABLE/);await until(()=>f.requests.length===1);await assert.rejects(client.post('receipts',encode({eventIds:[id(10)]})),/LOG_HTTP_UNAVAILABLE/);client.close();client.close();await rejection;await assert.rejects(client.post('batches',encode({})),/LOG_HTTP_UNAVAILABLE/);assert.equal(f.requests.length,1);
});

test('response size, encoding and media type are bounded; a redirect is never followed',async t=>{
 let mode=0;const f=await fixture(t,(row,req,res)=>{
  if(mode===0)respond(res,{},200,{'Content-Encoding':'gzip'});
  if(mode===1)respond(res,{},200,{'Content-Type':'text/plain'});
  if(mode===2)respond(res,'x',200,{'Content-Length':'40000'});
  if(mode===3){res.writeHead(200,{'Content-Type':'application/json'});res.end('x'.repeat(32769));}
  if(mode===4)respond(res,{},302,{Location:f.endpoint+'/forbidden'});
 });const client=transport(t,f.configuration);
 for(mode=0;mode<4;mode++)await assert.rejects(client.post('batches',encode({})),/LOG_HTTP_UNAVAILABLE/);
 mode=4;assert.equal((await client.post('batches',encode({}))).status,302);assert.equal(f.requests.length,5);assert.ok(f.requests.every(r=>r.path.endsWith('/batches')));
});

test('emit stays nonblocking during real delayed HTTPS and accepts only exact commit receipts',async t=>{
 let release;const held=new Promise(resolve=>release=resolve);t.after(()=>release());
 const f=await fixture(t,async(row,req,res)=>{await held;respond(res,commit(row.body.events.map(event=>event.eventId)));});const client=producer(t,f.configuration);
 assert.equal(client.emit(event()),'ACCEPTED');assert.equal(f.requests.length,0);assert.equal(client.emit(event()),'DUPLICATE');assert.equal(client.emit({...event(),message:'secret'}),'REJECTED');
 await until(()=>f.requests.length===1);assert.equal(client.status().unknown,1);assert.equal(client.status().committed,0);assert.equal(client.status().requestActive,true);assert.ok(!JSON.stringify(client.status()).includes(token));release();await until(()=>client.status().committed===1);assert.equal(client.status().pending,0);assert.equal(client.status().invalid,1);
 client.close();await client.whenClosed();assert.equal(client.status().closed,true);assert.equal(client.emit(event(11)),'CLOSED');
});

test('outage recovery checks receipts first and retries unchanged original IDs and bytes',async t=>{
 let batches=0;const f=await fixture(t,(row,req,res)=>{
  if(row.path.endsWith('/receipts'))return respond(res,{receipts:row.body.eventIds.map(eventId=>({eventId,state:'UNKNOWN'}))});
  if(++batches===1){req.socket.destroy();return;}
  respond(res,commit(row.body.events.map(event=>event.eventId)));
 });const client=producer(t,f.configuration);client.emit(event());await until(()=>client.status().committed===1);assert.deepEqual(f.requests.map(r=>r.path.split('/').at(-1)),['batches','receipts','batches']);assert.equal(f.requests[0].raw,f.requests[2].raw);assert.equal(client.status().deliveryFailures,1);
});

test('lost acknowledgement uses scoped COMMITTED lookup without resending committed batch',async t=>{
 const f=await fixture(t,(row,req,res)=>{if(row.path.endsWith('/batches'))req.socket.destroy();else respond(res,commit(row.body.eventIds));});const client=producer(t,f.configuration);client.emit(event());await until(()=>client.status().committed===1);assert.deepEqual(f.requests.map(r=>r.path.split('/').at(-1)),['batches','receipts']);assert.equal(client.status().pending,0);
});

test('202 and ambiguous JSON never commit; scoped expiry preserves historical uncertainty',async t=>{
 let lookups=0;const f=await fixture(t,(row,req,res)=>{
  if(row.path.endsWith('/batches'))return respond(res,commit([id(10)]),202);
  if(++lookups===1)return respond(res,'{"receipts":[{"eventId":"'+id(10)+'","state":"UNKNOWN","state":"COMMITTED","receivedAt":"2026-11-03T03:00:00Z"}]}');
  respond(res,{receipts:[{eventId:id(10),state:'EXPIRED'}]});
 });const client=producer(t,f.configuration);client.emit(event());await until(()=>client.status().expired===1,4000);assert.equal(client.status().committed,0);assert.equal(client.status().discardedUnknown,1);assert.equal(client.status().dropped,0);assert.equal(client.status().pending,0);assert.equal(client.status().deliveryFailures,2);
});

test('credential refusal becomes visible blocked state without relabeling or infinite requests',async t=>{
 const f=await fixture(t,(row,req,res)=>respond(res,{},403)),client=producer(t,f.configuration);client.emit(event());await until(()=>client.status().blocked);assert.equal(client.status().lastHttpStatus,403);assert.equal(client.status().committed,0);assert.equal(client.status().pending,1);client.emit(event(11));await pause(600);assert.equal(f.requests.length,1);assert.equal(client.status().pending,2);
});

test('whole five-second deadline aborts actual TLS response body and releases owned request', {timeout:8000},async t=>{
 const f=await fixture(t,(row,req,res)=>{res.writeHead(200,{'Content-Type':'application/json'});res.write('{');});const client=transport(t,f.configuration);const started=performance.now();await assert.rejects(client.post('batches',encode({})),/LOG_HTTP_UNAVAILABLE/);assert.ok(performance.now()-started>=4900);await until(()=>f.sockets.size===0);
});

test('close during actual request retains UNKNOWN and witnesses dispatcher exit',async t=>{
 const f=await fixture(t,()=>{}),client=producer(t,f.configuration);client.emit(event());await until(()=>f.requests.length===1);client.close();client.close();await client.whenClosed();await until(()=>f.sockets.size===0);assert.equal(client.status().requestActive,false);assert.equal(client.status().pending,1);assert.equal(client.status().unknown,1);assert.equal(client.status().committed,0);assert.equal(client.status().closed,true);
});

test('permanent batch rejection unblocks later events without automatic resubmission',async t=>{
 for(const status of [400,409,413])await t.test('HTTP '+status,async child=>{
  const f=await fixture(child,(row,req,res)=>{
   if(row.path.endsWith('/receipts'))return respond(res,{receipts:row.body.eventIds.map(eventId=>({eventId,state:'UNKNOWN'}))});
   if(row.body.events.some(item=>item.eventId===id(10)))return respond(res,{},status);
   respond(res,commit(row.body.events.map(item=>item.eventId)));
  });const client=producer(child,f.configuration);client.emit(event());
  await until(()=>client.status().rejected===1);assert.equal(client.status().pending,0);assert.equal(client.status().committed,0);assert.equal(client.status().dropped,0);
  client.emit(event(11));await until(()=>client.status().committed===1);await pause(600);
  assert.equal(f.requests.filter(row=>row.path.endsWith('/batches')&&row.body.events.some(item=>item.eventId===id(10))).length,1);
  assert.equal(client.status().blocked,false);assert.equal(client.status().pending,0);assert.equal(client.status().rejected,1);
 });
});

test('permanent rejection after earlier UNKNOWN preserves uncertainty while retiring local retry',async t=>{
 let batches=0;const f=await fixture(t,(row,req,res)=>{
  if(row.path.endsWith('/receipts'))return respond(res,{receipts:row.body.eventIds.map(eventId=>({eventId,state:'UNKNOWN'}))});
  if(++batches===1)return req.socket.destroy();respond(res,{},400);
 });const client=producer(t,f.configuration);client.emit(event());await until(()=>client.status().rejected===1);assert.equal(client.status().committed,0);assert.equal(client.status().discardedUnknown,1);assert.equal(client.status().dropped,0);assert.equal(client.status().pending,0);assert.equal(client.status().deliveryFailures,1);
 const count=f.requests.length;await pause(600);assert.equal(f.requests.length,count);
});
