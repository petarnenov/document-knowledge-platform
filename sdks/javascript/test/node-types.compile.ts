import { NodeLogProducer, type ExecutionIdentity, type LifecycleInput, type Vocabulary } from '../src/node.js';
import { FileLogSpool } from '../src/node-spool.js';
const identity: ExecutionIdentity = { buildId:'original-build', runId:'run', instanceId:'instance', component:'tool', gitSha:'source', sourceFingerprint:'fingerprint' };
const vocabulary: Vocabulary = { events:['build.started'], phases:['BUILD'] };
const event: LifecycleInput = { eventId:'id',timestamp:'UTC',severity:'DEBUG',eventName:'build.started',correlationId:'id',phase:'BUILD',outcome:'STARTED',durationMs:0,attempt:0,count:1,cleanupSettled:false };
const client = new NodeLogProducer(identity,vocabulary,{endpoint:'https://logger.example/api/v1/dev-logs',producerToken:'scoped',ca:new Uint8Array()});
client.emit(event);client.close();const closed:Promise<void>=client.whenClosed();void closed;
// @ts-expect-error Explicit CA is required; there is no TLS verification bypass option.
new NodeLogProducer(identity,vocabulary,{endpoint:'https://logger.example/api/v1/dev-logs',producerToken:'scoped',rejectUnauthorized:false});
// @ts-expect-error No registrar credential is part of a producer configuration.
new NodeLogProducer(identity,vocabulary,{endpoint:'https://logger.example/api/v1/dev-logs',producerToken:'scoped',ca:'public-ca',registrarToken:'secret'});
// @ts-expect-error Domain authority cannot be claimed through debug metadata.
client.emit({...event,subject:'forged'});
async function durable():Promise<void> {
 const spool=await FileLogSpool.open('/private/original-spool',identity,vocabulary,'original-credential-fingerprint');
 const producer=new NodeLogProducer(identity,vocabulary,{endpoint:'https://logger.example/api/v1/dev-logs',producerToken:'scoped',ca:'public-ca'},spool);
 const pending:number=producer.status().unspooled;const lease:boolean=producer.status().leaseReleased;void pending;void lease;
 producer.close();await producer.whenClosed();
}
void durable;
// @ts-expect-error Port replacement must be asynchronous; emit cannot synchronously write a file.
new NodeLogProducer(identity,vocabulary,{endpoint:'https://logger.example/api/v1/dev-logs',producerToken:'scoped',ca:'ca'},{load:()=>[],replace:()=>{},close:async()=>{}});
