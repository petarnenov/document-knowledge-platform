import { BrowserLogProducer, type ExecutionIdentity, type LifecycleInput, type Vocabulary } from '../src/browser.js';
const identity: ExecutionIdentity = { buildId:'original-build', runId:'separate-run', instanceId:'instance', component:'admin-portal', gitSha:'source', sourceFingerprint:'fingerprint' };
const vocabulary: Vocabulary = { events:['process.started'], phases:['STARTUP'] };
const input: LifecycleInput = { eventId:'id', timestamp:'UTC', severity:'DEBUG', eventName:'process.started', correlationId:'operation', phase:'STARTUP', outcome:'STARTED', durationMs:0, attempt:0, count:1, cleanupSettled:false };
const producer = new BrowserLogProducer(identity, vocabulary, {environment:window});
producer.emit(input); void producer.connect('csrf'); void producer.reconcile(); producer.flush(); producer.retire(); producer.close();
const pending: number = producer.status().pending; void pending;
// @ts-expect-error Arbitrary messages are not typed diagnostics.
producer.emit({...input,message:'document text'});
// @ts-expect-error Identity authority cannot be supplied by browser events.
producer.emit({...input,tenantId:'forged'});
// @ts-expect-error An event outcome is a closed vocabulary.
producer.emit({...input,outcome:'anything'});
// @ts-expect-error Delivery counters are readonly and cannot fabricate persistence.
producer.status().committed = 10;
// @ts-expect-error Browser configuration has no logger bearer credential.
new BrowserLogProducer(identity,vocabulary,{producerToken:'secret'});
