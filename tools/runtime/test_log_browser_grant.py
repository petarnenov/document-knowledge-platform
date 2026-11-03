"""Real private configuration transition; Docker replacement is an explicit unit fixture."""
import copy
import contextlib
import datetime as dt
import io
import json
from pathlib import Path
import unittest
import sys
import uuid
from unittest.mock import patch
import test_dev_logging as fixture

m = fixture.m


class BrowserGrantTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.LoggingRuntimeTests(); self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        self.state = self.fixture.ready(); self.directory = self.fixture.directory.resolve(strict=True)
        self.state['directory'] = str(self.directory)
        m.create_writer_files(self.directory)
        self.state.update(writerEnabled=True, viewerEnabled=True, writerActivation={'stage':'ACTIVE'},
                          configSha256=m.config_digest(self.directory,True))
        authority = self.directory/'gateway-pki/authority'; authority.mkdir(mode=0o700)
        self.issuer = m.security_bootstrap().origins(self.state['port'],self.state['idpPort'])[1]
        m.write_private(authority/'grants.json', json.dumps({'schemaVersion':1,'entries':[
            {'issuer':self.issuer,'tenant':'tenant-a'},{'issuer':self.issuer,'tenant':'tenant-b'}]}))
        self.value = {'id':str(uuid.uuid4()), 'expiresAt':(dt.datetime.now(dt.timezone.utc)+dt.timedelta(hours=1)).isoformat().replace('+00:00','Z'),
            'tokenSha256':'f'*64, 'parents':[str(uuid.uuid4())], 'bridgeManifests':['e'*64],
            'permissions':[{'kind':'AUTHENTICATED_UNSELECTED','issuer':self.issuer},
                           {'kind':'TENANT','issuer':self.issuer,'tenantId':'tenant-a'}]}
        self.file = self.directory/'operator/browser-grant.json'; self.write()
        self.identities = {name:{'Id':name+'-original','Image':name+'-image'} for name in ('postgres','gateway','viewer')}
        self.identities['query'] = {'Id':'query-original','Image':self.state['imageId']}
        for replacement in (patch.object(m,'verify_live',return_value={'result':'PASS'}),patch.object(m,'check_resources'),
                patch.object(m,'assert_schema'),patch.object(m,'container',side_effect=lambda state,directory,service:self.identities[service]),
                patch.object(m,'inspect',return_value={'Config':{'Labels':{**m.labels(self.state),'dkp.logging.source':self.state['sourceFingerprint']}}}),
                patch.object(m,'start',side_effect=self.started)):
            replacement.start(); self.addCleanup(replacement.stop)

    def write(self):
        m.write_private(self.file,json.dumps(self.value),replace=self.file.exists())

    def started(self, state, directory, **kwargs):
        self.assertEqual({'update':True,'target':'query'},kwargs)
        state['configSha256'] = m.config_digest(directory,True)
        return {'result':'PASS'}

    def install(self): return m.install_browser_grant(self.state,self.directory,self.file)

    def test_exact_finite_grant_query_only_update_preserves_credentials_and_is_idempotent(self):
        before = m.writer_bindings(self.directory)
        self.assertEqual('INSTALLED',self.install()['result'])
        after = m.writer_bindings(self.directory)
        self.assertEqual({k:v for k,v in before.items() if k != 'query/registration-grants.json'},
                         {k:v for k,v in after.items() if k != 'query/registration-grants.json'})
        self.assertEqual(after,self.state['writerActivation']['files'])
        self.assertNotIn('browserGrantUpdate',self.state)
        self.assertEqual('UNCHANGED',self.install()['result']);self.assertEqual(1,m.start.call_count)
        row=json.loads(m.private(self.directory/'query/registration-grants.json'))
        self.assertEqual([self.value],row['delegators']);self.assertEqual(1,len(row['registrars']))
        self.assertNotIn('f'*64,json.dumps(self.install()))

    def test_unknown_roles_issuer_tenant_fields_ids_expiry_and_nonfinite_grants_reject_before_update(self):
        original=copy.deepcopy(self.value);before=m.writer_bindings(self.directory)
        cases=[{'permissions':[]},{'permissions':[{'kind':'OPERATOR_ONLY'}]},
            {'permissions':[{'kind':'UNATTRIBUTED'}]},
            {'permissions':[{'kind':'TENANT','issuer':self.issuer,'tenantId':'unknown'}]},
            {'permissions':[{'kind':'AUTHENTICATED_UNSELECTED','issuer':'https://foreign.invalid'}]},
            {'permissions':[original['permissions'][0]]*2},{'parents':[]},{'parents':original['parents']*2},
            {'parents':['00000000-0000-0000-0000-000000000000']},{'id':'not-an-id'},
            {'bridgeManifests':[]},{'bridgeManifests':['e'*64]*2},{'bridgeManifests':['wrong']},
            {'bridgeManifests':['e'*64]*65},{'tokenSha256':'raw-token'},
            {'expiresAt':'2020-01-01T00:00:00Z'}, {'expiresAt':'2099-01-01T00:00:00Z'},
            {'expiresAt':'2026-11-03'}, {'extra':'untrusted'}]
        for case in cases:
            with self.subTest(case=case):
                self.value={**original,**case};self.write()
                with self.assertRaises((m.RuntimeFailure,ValueError)):self.install()
                self.assertEqual(before,m.writer_bindings(self.directory));self.assertNotIn('browserGrantUpdate',self.state)
        m.start.assert_not_called()

    def test_private_canonical_duplicate_free_grant_and_active_writer_required(self):
        for file in (None,Path('relative')):
            with self.assertRaises(m.RuntimeFailure):m.install_browser_grant(self.state,self.directory,file)
        link=self.directory/'operator/link';link.symlink_to(self.file)
        with self.assertRaises(m.RuntimeFailure):m.install_browser_grant(self.state,self.directory,link)
        self.file.chmod(0o644)
        with self.assertRaises(m.RuntimeFailure):self.install()
        self.file.chmod(0o600);self.file.write_text('{"id":"one","id":"two"}')
        with self.assertRaises(m.RuntimeFailure):self.install()
        self.state['writerEnabled']=False
        with self.assertRaises(m.RuntimeFailure):self.install()
        m.start.assert_not_called()

    def test_cross_role_id_or_hash_reuse_and_changed_existing_grant_are_rejected(self):
        original=copy.deepcopy(self.value)
        registrar=json.loads(m.private(self.directory/'query/registration-grants.json'))['registrars'][0]
        for field in ('id','tokenSha256'):
            self.value={**original,field:registrar[field]};self.write()
            with self.assertRaisesRegex(m.RuntimeFailure,'CONFLICT'):self.install()
        self.value=original;self.write();self.install();m.start.reset_mock()
        self.value['parents']=[str(uuid.uuid4())];self.write()
        with self.assertRaisesRegex(m.RuntimeFailure,'CONFLICT'):self.install()
        m.start.assert_not_called()

    def test_interrupted_query_update_resumes_only_the_same_recorded_configuration(self):
        m.start.side_effect=m.RuntimeFailure('SYNTHETIC_QUERY_UPDATE_FAILURE')
        with self.assertRaises(m.RuntimeFailure):self.install()
        self.assertIn('browserGrantUpdate',self.state)
        m.start.side_effect=self.started
        self.assertEqual('INSTALLED',self.install()['result'])
        self.assertNotIn('browserGrantUpdate',self.state)
        self.assertEqual([self.value],json.loads(m.private(self.directory/'query/registration-grants.json'))['delegators'])

    def test_resume_rejects_unrelated_file_or_preserved_container_drift(self):
        m.start.side_effect=m.RuntimeFailure('SYNTHETIC_QUERY_UPDATE_FAILURE')
        with self.assertRaises(m.RuntimeFailure):self.install()
        m.start.side_effect=self.started;m.start.reset_mock()
        file=self.directory/'query/cursor-key';before=m.private(file);m.write_private(file,'changed-key',replace=True)
        with self.assertRaisesRegex(m.RuntimeFailure,'DRIFT'):self.install()
        m.write_private(file,before,replace=True)
        self.identities['gateway']['Id']='foreign-gateway'
        with self.assertRaisesRegex(m.RuntimeFailure,'DRIFT'):self.install()
        m.start.assert_not_called()

    def test_mutation_during_update_keeps_incomplete_plan_and_never_claims_success(self):
        def changed(*args,**kwargs):self.identities['postgres']['Id']='replacement-postgres'
        m.start.side_effect=changed
        with self.assertRaisesRegex(m.RuntimeFailure,'DRIFT'):self.install()
        self.assertIn('browserGrantUpdate',self.state)

    def test_unknown_query_image_cannot_be_adopted_during_resume(self):
        m.start.side_effect=m.RuntimeFailure('SYNTHETIC_QUERY_UPDATE_FAILURE')
        with self.assertRaises(m.RuntimeFailure):self.install()
        self.identities['query']['Image']='unknown-query-image';m.start.reset_mock()
        with self.assertRaisesRegex(m.RuntimeFailure,'DRIFT'):self.install()
        m.start.assert_not_called()

    def test_cli_dispatch_and_incomplete_update_blocks_unrelated_mutation(self):
        argv=['dev-logging.py','install-browser-grant','--directory',str(self.directory),'--grant',str(self.file)]
        with patch.object(sys,'argv',argv),patch.object(m,'local_daemon'),patch.object(m,'initialize',return_value=self.state),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,m.main())
        self.assertEqual('INSTALLED',json.loads(output.getvalue())['result'])
        self.state['browserGrantUpdate']={'incomplete':'synthetic'}
        argv=['dev-logging.py','renew-readers','--directory',str(self.directory)]
        with patch.object(sys,'argv',argv),patch.object(m,'local_daemon'),patch.object(m,'initialize',return_value=self.state),patch.object(m,'renew') as renew,contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1,m.main())
        self.assertEqual('LOG_BROWSER_GRANT_UPDATE_PENDING',json.loads(output.getvalue())['code']);renew.assert_not_called()


if __name__ == '__main__':unittest.main()
