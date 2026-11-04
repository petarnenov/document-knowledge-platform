"""Typed original-catalog diagnostics, privacy, exact replay and durable SDK recovery."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from dkp_logging.wire import Wire, Prepared, encode, MAX_INTEGER
from dkp_logging.producer import LogProducer
from dkp_logging.spool import FileLogSpool
from test_wire import identity, vocabulary, event
from test_producer import Transport, until


def catalog():
    return {**vocabulary(), 'errorCodes': ['OPERATION_FAILED'],
        'exceptionClasses': ['java.io.IOException', 'example.WorkerFailure'],
        'dependencies': [{'name': 'example.parser', 'version': '1.0', 'vendorBuildProvenance': 'UNKNOWN'}],
        'frames': [{'symbol': 'example.Worker.inspect', 'source': 'workers/structure-inspector/src/main/java/example/Worker.java'}]}


def diagnostic():
    return dict(status=422, remainingDeadlineMs=17, errorCode='OPERATION_FAILED', sqlState='42501',
        dependency=copy.deepcopy(catalog()['dependencies'][0]),
        failure=dict(exceptionClass='example.WorkerFailure', causeClasses=['java.io.IOException'],
            stackFrames=[{**catalog()['frames'][0], 'line': 42}]),
        attributes=dict(bytes=0, queueDepth=1, cancelRequested=False),
        counts=dict(redactedFields=2, truncatedFields=1, unregisteredSymbols=0))


class DiagnosticTests(unittest.TestCase):
    def setUp(self):
        self.identity=identity();self.wire=Wire(self.identity,catalog())

    def prepare(self, diagnostics):
        return self.wire.prepare({**event(), 'diagnostics': diagnostics})

    def test_complete_typed_context_and_exact_original_replay(self):
        item={**event(), 'diagnostics':diagnostic()}
        prepared=self.wire.prepare(item);row=json.loads(prepared.data);context=row['context']
        self.assertEqual(422,context['status']);self.assertEqual('42501',context['sqlState'])
        self.assertEqual('example.parser',context['dependency']);self.assertEqual('1.0',context['dependencyVersion'])
        self.assertEqual('UNKNOWN',context['attributes']['vendorBuildProvenance'])
        self.assertEqual('example.WorkerFailure',context['exceptionClass'])
        self.assertEqual(['java.io.IOException'],context['causeClasses'])
        self.assertEqual(42,context['stackFrames'][0]['line'])
        self.assertEqual(2,context['redactedFields']);self.assertNotIn('unregisteredSymbols',context)
        self.assertEqual(prepared,self.wire.restore(prepared.data))
        self.assertEqual(item['eventId'],prepared.event_id)
        self.assertEqual(item['timestamp'],row['timestamp'])

    def test_legacy_lifecycle_bytes_and_copied_catalog_remain_unchanged(self):
        item=event();self.assertEqual(Wire(self.identity,vocabulary()).prepare(item),self.wire.prepare(item))
        original=catalog();wire=Wire(self.identity,original)
        original['exceptionClasses'].clear();original['frames'][0]['source']='changed.java'
        original['dependencies'][0]['version']='changed';original['errorCodes'].clear()
        self.assertEqual('example.WorkerFailure',json.loads(wire.prepare({**item,'diagnostics':diagnostic()}).data)['context']['exceptionClass'])

    def test_unregistered_concrete_class_causes_and_source_are_omitted_without_superclass_substitution(self):
        value=diagnostic();value['failure']={'exceptionClass':'example.UnregisteredSubclass',
            'causeClasses':['example.UnregisteredCause','java.io.IOException'],
            'stackFrames':[{'symbol':'example.Unknown.call'},
                {'symbol':'example.Worker.inspect','source':'wrong/public.java','line':1},
                {'symbol':'example.Worker.inspect','line':3}]}
        row=json.loads(self.prepare(value).data);context=row['context']
        self.assertNotIn('exceptionClass',context);self.assertEqual(['java.io.IOException'],context['causeClasses'])
        self.assertEqual([{'symbol':'example.Worker.inspect','line':3}],context['stackFrames'])
        self.assertEqual(4,context['unregisteredSymbols'])
        self.assertNotIn('Unregistered',str(row));self.assertNotIn('wrong/public',str(row))
        self.assertEqual(row,json.loads(self.wire.restore(encode(row)).data))

    def test_closed_fields_types_bounds_and_raw_content_are_rejected(self):
        cases=[{'message':'SECRET'}, {'status':True},{'status':99},{'status':600},
            {'remainingDeadlineMs':-1},{'remainingDeadlineMs':MAX_INTEGER+1}, {'errorCode':'UNKNOWN'},
            {'sqlState':'raw-secret'}, {'attributes':{'token':'SECRET'}}, {'attributes':{'bytes':True}},
            {'attributes':{'queueDepth':-1}}, {'attributes':{'cancelRequested':1}},
            {'counts':{'redactedFields':-1}},{'counts':{'truncatedFields':True}},
            {'counts':{'unregisteredSymbols':MAX_INTEGER},'failure':{'causeClasses':['unknown.Type'],'stackFrames':[]}},
            {'dependency':{**catalog()['dependencies'][0],'version':'forged'}},
            {'failure':{'message':'SECRET','causeClasses':[],'stackFrames':[]}},
            {'failure':{'causeClasses':['java.io.IOException']*5,'stackFrames':[]}},
            {'failure':{'causeClasses':[],'stackFrames':[{'symbol':'example.Worker.inspect'}]*33}},
            {'failure':{'causeClasses':[False],'stackFrames':[]}},
            {'failure':{'causeClasses':[],'stackFrames':[{'symbol':'example.Worker.inspect','source':'../secret'}]}},
            {'failure':{'causeClasses':[],'stackFrames':[{'symbol':'example.Worker.inspect','line':True}]}},
            {'failure':{'causeClasses':[],'stackFrames':[{'symbol':'example.Worker.inspect','extra':'SECRET'}]}}]
        for value in cases:
            with self.subTest(value=value),self.assertRaises(ValueError):self.prepare(value)

    def test_broad_original_dependency_catalog_does_not_widen_emitted_context(self):
        for name in ('AWS_S3','a'*97,'a'*128):
            vocab=catalog();vocab['dependencies'][0]['name']=name
            wire=Wire(self.identity,vocab);item=event()
            self.assertEqual(Wire(self.identity,vocabulary()).prepare(item),wire.prepare(item))
            with self.assertRaises(ValueError):
                wire.prepare({**item,'diagnostics':{'dependency':vocab['dependencies'][0]}})
        vocab=catalog();vocab['dependencies'][0]['name']='a'*129
        with self.assertRaises(ValueError):Wire(self.identity,vocab)

    def test_invalid_or_unbounded_catalog_is_rejected(self):
        for key,value in [('exceptionClasses',['bad class']),('errorCodes',['bad code']),
                ('exceptionClasses',['java.io.IOException']*2),('frames',[{'symbol':'x','source':'../secret'}]),
                ('dependencies',[{**catalog()['dependencies'][0],'vendorBuildProvenance':'FORGED'}]),
                ('dependencies',[catalog()['dependencies'][0]]*2),('frames',catalog()['frames']*2),
                ('dependencies',[{'name':'a','version':'v','vendorBuildProvenance':'UNKNOWN','extra':'secret'}]),
                ('frames',[]*0+catalog()['frames']*32769)]:
            with self.subTest(key=key),self.assertRaises(ValueError):Wire(self.identity,{**catalog(),key:value})

    def test_tampered_spool_diagnostics_are_rejected_instead_of_resanitized(self):
        row=json.loads(self.prepare(diagnostic()).data)
        cases=[]
        for key,value in [('exceptionClass','unknown.Type'),('stackFrames',[{'symbol':'unknown.Method'}]),
                ('status',False),('redactedFields',0),('secret','SECRET'),('dependencyVersion','forged')]:
            changed=copy.deepcopy(row);changed['context'][key]=value;cases.append(changed)
        changed=copy.deepcopy(row);changed['context']['attributes']['vendorBuildProvenance']='VERIFIED';cases.append(changed)
        for changed in cases:
            with self.subTest(context=changed['context']),self.assertRaises(ValueError):self.wire.restore(encode(changed))

    def test_optional_empty_diagnostics_and_dependency_image_and_zero_counts(self):
        value=diagnostic();value['dependency']['imageDigest']='sha256:'+'a'*64
        vocab=catalog();vocab['dependencies'][0]['imageDigest']=value['dependency']['imageDigest']
        wire=Wire(self.identity,vocab);prepared=wire.prepare({**event(),'diagnostics':value})
        self.assertEqual(prepared,wire.restore(prepared.data))
        row=json.loads(self.prepare({'counts':dict(redactedFields=0,truncatedFields=0,unregisteredSymbols=0)}).data)
        self.assertNotIn('redactedFields',row['context'])
        plain=event();self.assertEqual(self.wire.prepare(plain),self.wire.prepare({**plain,'diagnostics':{}}))

    def test_durable_original_diagnostic_event_recovers_and_commits_without_identity_rewrite(self):
        fingerprint=hashlib.sha256(b'unit-only-credential').hexdigest()
        class BoundTransport(Transport):
            def credential_fingerprint(self):return fingerprint
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory).resolve()/'queue';spool=FileLogSpool(path,self.identity,fingerprint)
            prepared=self.prepare(diagnostic());spool.load();spool.replace([prepared]);spool.close()
            transport=BoundTransport();transport.lookup='UNKNOWN';restored=FileLogSpool(path,self.identity,fingerprint)
            producer=LogProducer(self.identity,catalog(),transport,restored)
            try:
                until(lambda:producer.status()['committed']==1)
                delivered=next(json.loads(data)['events'][0] for route,data in transport.calls if route=='batches')
                self.assertEqual(prepared.event_id,delivered['eventId'])
                self.assertEqual(json.loads(prepared.data),delivered)
            finally:producer.close();producer.await_closed()

    def test_reordered_diagnostic_restart_duplicates_preserve_original_bytes_and_reject_conflicts(self):
        fingerprint=hashlib.sha256(b'owned-replay-test-credential').hexdigest()
        class BoundTransport(Transport):
            def credential_fingerprint(self):return fingerprint
        item={**event(),'diagnostics':diagnostic()}
        prepared=self.wire.prepare(item)
        def reorder(value):
            if isinstance(value,dict):return {key:reorder(child) for key,child in reversed(list(value.items()))}
            if isinstance(value,list):return [reorder(child) for child in value]
            return value
        saved=Prepared(prepared.event_id,encode(reorder(json.loads(prepared.data))))
        self.assertNotEqual(prepared.data,saved.data)
        self.assertEqual(json.loads(prepared.data),json.loads(saved.data))
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory).resolve()/'queue';spool=FileLogSpool(path,self.identity,fingerprint)
            spool.load();spool.replace([saved]);spool.close()
            restored=FileLogSpool(path,self.identity,fingerprint)
            transport=BoundTransport();transport.lookup='UNKNOWN';transport.release.clear()
            producer=LogProducer(self.identity,catalog(),transport,restored)
            try:
                self.assertTrue(transport.entered.wait(1))
                self.assertEqual('DUPLICATE',producer.emit(item))
                changed=copy.deepcopy(item);changed['diagnostics']['status']=503
                self.assertEqual('REJECTED',producer.emit(changed))
                self.assertEqual([saved],restored.load())
                self.assertEqual(1,producer.status()['pending'])
                self.assertEqual(1,producer.status()['rejected'])
                transport.release.set();until(lambda:producer.status()['committed']==1)
                batch=next(data for route,data in transport.calls if route=='batches')
                self.assertEqual(b'{"schemaVersion":1,"events":['+saved.data+b']}',batch)
            finally:
                transport.release.set();producer.close();self.assertTrue(producer.await_closed())
