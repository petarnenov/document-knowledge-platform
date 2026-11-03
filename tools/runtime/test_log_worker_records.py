import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest
import uuid

HERE = Path(__file__).resolve().parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value


records = module('worker_records_test_subject', HERE / 'log-worker-records.py')


class WorkerRecordsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.wire = module('worker_records_test_wire', HERE.parents[1] / 'sdks/python/dkp_logging/wire.py')
        cls.addClassCleanup(sys.modules.pop, 'worker_records_test_wire', None)

    def setUp(self):
        self.identity = dict(buildId=str(uuid.uuid4()), runId=str(uuid.uuid4()), instanceId=str(uuid.uuid4()), component='structure-inspector', gitSha='a' * 40, sourceFingerprint='b' * 64)
        self.vocabulary = dict(events=sorted(records.EVENTS), phases=sorted(records.PHASES), errorCodes=['OPERATION_FAILED'], dependencies=[], exceptionClasses=['java.io.IOException'], frames=[dict(symbol='worker.inspect', source='workers/Inspector.java')])
        self.event = dict(eventId=str(uuid.uuid4()), timestamp='2026-11-03T22:00:00.123456789Z', severity='ERROR', eventName='operation.failed', correlationId=str(uuid.uuid4()), phase='OPERATION', outcome='FAILED', durationMs=12, attempt=0, count=0, cleanupSettled=False, diagnostics=dict(errorCode='OPERATION_FAILED', failure=dict(exceptionClass='java.io.IOException', causeClasses=[], stackFrames=[dict(symbol='worker.inspect', source='workers/Inspector.java', line=42)])))
        self.sent = []
        self.reader = records.WorkerRecords(self.identity, self.vocabulary, 'c' * 64, self.wire.Wire, self.emit)

    def emit(self, event):
        self.sent.append(copy.deepcopy(event))
        return 'ACCEPTED'

    def record(self):
        return dict(schemaVersion=1, **{key: self.identity[key] for key in ('buildId', 'runId', 'instanceId')}, manifestSha256='c' * 64, event=copy.deepcopy(self.event), loss=dict(dropped=0, rejected=0, writerFailures=0))

    def line(self, value=None):
        return records.PREFIX + records.encode(self.record() if value is None else value) + b'\n'

    def test_fragmented_original_record_keeps_identity_time_and_registered_source(self):
        raw = self.line()
        for byte in raw:
            self.reader.feed(bytes([byte]))
        self.assertEqual([self.event], self.sent)
        prepared = json.loads(self.reader.codec.prepare(self.sent[0]).data)
        for key, value in self.identity.items():
            self.assertEqual(value, prepared[key])
        self.assertEqual(self.event['eventId'], prepared['eventId'])
        self.assertEqual(self.event['timestamp'], prepared['timestamp'])
        self.assertEqual(1, self.reader.status()['accepted'])
        self.assertNotIn('COMMITTED', str(self.reader.status()))

    def test_closed_shape_duplicate_json_identity_types_catalog_and_counts_fail_closed(self):
        invalid = []
        for key, value in [('schemaVersion', True), ('runId', str(uuid.uuid4())), ('manifestSha256', 'd' * 64), ('event', []), ('loss', []), ('unknown', 'PRIVATE_CANARY')]:
            row = self.record();row[key] = value;invalid.append(self.line(row))
        for key, value in [('phase', 'DATABASE'), ('eventName', 'build.started'), ('count', True), ('durationMs', -1), ('unknown', 'PRIVATE_CANARY')]:
            row = self.record();row['event'][key] = value;invalid.append(self.line(row))
        for mutation in ('class', 'cause', 'frame', 'source'):
            row = self.record();failure = row['event']['diagnostics']['failure']
            if mutation == 'class':failure['exceptionClass'] = 'private.Unknown'
            elif mutation == 'cause':failure['causeClasses'] = ['private.Unknown']
            elif mutation == 'frame':failure['stackFrames'][0]['symbol'] = 'private.unknown'
            else:failure['stackFrames'][0]['source'] = 'private/Wrong.java'
            invalid.append(self.line(row))
        for loss in [dict(dropped=True, rejected=0, writerFailures=0), dict(dropped=-1, rejected=0, writerFailures=0), dict(dropped=0, rejected=0), dict(dropped=0, rejected=0, writerFailures=records.MAX_INTEGER+1)]:
            row=self.record();row['loss']=loss;invalid.append(self.line(row))
        invalid += [records.PREFIX+b'{"schemaVersion":1,"schemaVersion":1}\n',records.PREFIX+b'\xff\n',records.PREFIX+b'[]\n',records.PREFIX+b'{}{}\n',records.PREFIX+b'{"event":NaN}\n']
        for raw in invalid:self.reader.feed(raw)
        self.assertEqual([],self.sent);self.assertEqual(len(invalid),self.reader.status()['rejected']);self.assertNotIn('PRIVATE',str(self.reader.status()))

    def test_oversized_fragmented_line_is_drained_with_bounded_memory_and_next_record_recovers(self):
        self.reader.feed(records.PREFIX+b'x'*8000)
        self.reader.feed(b'x'*40000)
        self.assertLessEqual(self.reader.status()['bufferedBytes'],8191)
        self.assertTrue(self.reader.status()['draining'])
        self.reader.feed(b'\nordinary nonprotocol output\n'+self.line())
        self.assertEqual([self.event],self.sent);self.assertEqual(1,self.reader.status()['oversized']);self.assertEqual(1,self.reader.status()['ignored'])
        self.reader.feed(b'partial');self.reader.finish();self.reader.finish()
        self.assertEqual('EOF',self.reader.status()['streamState']);self.assertTrue(self.reader.status()['incomplete']);self.assertEqual(0,self.reader.status()['bufferedBytes']);self.assertRaises(ValueError,self.reader.feed,b'')

    def test_loss_is_monotonic_and_sdk_admission_uncertainty_is_visible(self):
        row=self.record();row['loss']['dropped']=2;self.reader.feed(self.line(row));self.reader.feed(self.line())
        self.assertEqual(1,len(self.sent));self.assertEqual(1,self.reader.status()['rejected'])
        for result in ('DUPLICATE','FULL','REJECTED','CLOSED'):
            self.reader.emit=lambda event,result=result:result;self.reader.feed(self.line(row));self.assertEqual(result,self.reader.status()['lastAdmission'])
        for callback in (lambda event:'PRIVATE_UNKNOWN_RESULT',lambda event:(_ for _ in ()).throw(OSError('PRIVATE_CANARY'))):
            self.reader.emit=callback;self.reader.feed(self.line(row))
        self.assertEqual(2,self.reader.status()['emitFailures']);self.assertEqual('EMIT_FAILED',self.reader.status()['lastAdmission']);self.assertNotIn('PRIVATE',str(self.reader.status()))
        self.reader.finish();self.assertFalse(self.reader.status()['incomplete'])

    def test_bad_configuration_and_nonbytes_input_are_rejected(self):
        for sha in ('x'*64,None):self.assertRaises(ValueError,records.WorkerRecords,self.identity,self.vocabulary,sha,self.wire.Wire,self.emit)
        wrong=dict(self.identity,component='document-management');self.assertRaises(ValueError,records.WorkerRecords,wrong,self.vocabulary,'c'*64,self.wire.Wire,self.emit)
        self.assertRaises(ValueError,self.reader.feed,'PRIVATE')
        self.reader.feed(b'x'*9000);self.reader.finish();self.assertTrue(self.reader.status()['incomplete'])


if __name__ == '__main__':unittest.main()
