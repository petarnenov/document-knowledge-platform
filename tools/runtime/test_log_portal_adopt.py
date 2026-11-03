"""Real private artifacts/state; explicit Docker/native fixtures and separate real acceptance."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import test_log_portal_selection as fixture

spec=importlib.util.spec_from_file_location('portal_adopt',Path(__file__).with_name('log-portal-adopt.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class PortalAdoptTest(unittest.TestCase):
    def setUp(self):
        self.fixture=fixture.PortalSelectionTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.r=fixture.m;self.state=self.fixture.state;self.directory=self.fixture.directory
        replacement=patch.object(m,'r',self.r);replacement.start();self.addCleanup(replacement.stop)
        self.selected=self.state.pop('portalRuntime');self.r.create_writer_files(self.directory)
        self.state.update(writerEnabled=True,viewerEnabled=True,volumes={'owned-volume':'a'*64},
            configSha256=self.r.config_digest(self.directory,True))
        self.native={'status':{'result':'PASS','pid':1234,'port':58480,'buildId':'11111111-1111-1111-1111-111111111111'},
            'stateSha256':'b'*64,'settingsSha256':'c'*64,'portalManifests':[self.selected['manifestSha256']]}
        self.config={'schemaVersion':1,'portalContext':self.selected['context'],'portalContextSha256':self.selected['contextSha256'],
            'nativeSettings':str(self.directory/'operator/native-settings.json'),'nativeDirectory':str(self.directory/'operator/native')}
        self.file=self.directory/'operator/adopt-settings.json';self.write()
        self.identities={service:{'Id':service+'-original','Image':service+'-image','State':{'Running':True}} for service in ('postgres','query','viewer')}
        self.identities['gateway']={'Id':'gateway-original','Image':self.state['gatewayImageId'],'State':{'Running':True}}
        self.after={'gatewayImage':'gateway:new','gatewayImageId':'sha256:'+'e'*64,'gatewaySourceFingerprint':'f'*64}
        for replacement in (patch.object(m,'native_identity',return_value=self.native),patch.object(m,'bridge_probe',return_value={'result':'PASS'}),
                patch.object(m,'served_assets',return_value=3),patch.object(self.r,'verify_live',return_value={'result':'PASS'}),
                patch.object(self.r,'check_resources'),patch.object(self.r,'assert_schema'),
                patch.object(self.r,'container',side_effect=lambda state,directory,service:self.identities[service]),
                patch.object(self.r,'inspect',side_effect=lambda kind,name:{'Config':{'Labels':{**self.r.labels(self.state),'dkp.logging.source':
                    self.after['gatewaySourceFingerprint'] if name==self.after['gatewayImageId'] else 'b'*64}}}),
                patch.object(self.r,'build_image',return_value=self.after),patch.object(self.r,'start',side_effect=self.started)):
            replacement.start();self.addCleanup(replacement.stop)

    def write(self): self.r.write_private(self.file,json.dumps(self.config),replace=self.file.exists())

    def started(self,state,directory,**kwargs):
        self.assertEqual({'update':True,'target':'gateway','prepared_images':{'gateway':self.after}},kwargs)
        state.update(self.after);self.identities['gateway']['Image']=self.after['gatewayImageId']
        self.r.gateway_config(directory,state['port'],state['idpPort'],viewer=True,writer=True,native_bff_port=58480)
        state['gatewayConfigSha256']=self.r.gateway_digest(directory)

    def adopt(self): return m.adopt(self.state,self.directory,self.file)

    def test_gateway_only_adoption_preserves_shared_ownership_and_repeat_is_idempotent(self):
        before=self.r.writer_bindings(self.directory);original=copy.deepcopy(self.identities)
        self.assertEqual('ADOPTED',self.adopt()['result'])
        self.assertEqual(before,self.r.writer_bindings(self.directory));self.assertNotIn('portalAdoption',self.state)
        self.assertEqual(self.selected,self.state['portalRuntime'])
        for service in ('postgres','query','viewer'):self.assertEqual(original[service],self.identities[service])
        self.assertEqual('UNCHANGED',self.adopt()['result']);self.assertEqual(1,self.r.start.call_count)
        self.assertEqual(1,self.r.build_image.call_count);self.assertEqual(1,m.bridge_probe.call_count)

    def test_interrupted_gateway_change_retains_exact_plan_and_resumes_same_selection(self):
        def interrupted(state,directory,**kwargs):
            self.r.gateway_config(directory,state['port'],state['idpPort'],viewer=True,writer=True,native_bff_port=58480)
            raise self.r.RuntimeFailure('OWNED_SIMULATED_INTERRUPTION')
        self.r.start.side_effect=interrupted
        with self.assertRaises(self.r.RuntimeFailure):self.adopt()
        saved=copy.deepcopy(self.state['portalAdoption']);self.assertEqual(self.selected,self.state['portalRuntime'])
        self.r.start.side_effect=self.started
        self.assertEqual('ADOPTED',self.adopt()['result']);self.assertEqual(1,self.r.build_image.call_count)
        self.assertEqual(self.selected,saved['selection']);self.assertNotIn('portalAdoption',self.state)

    def test_drift_or_unreachable_native_fails_before_gateway_replacement(self):
        m.bridge_probe.side_effect=self.r.RuntimeFailure('LOG_PORTAL_NATIVE_UNREACHABLE')
        with self.assertRaises(self.r.RuntimeFailure):self.adopt()
        self.r.start.assert_not_called();self.assertNotIn('portalRuntime',self.state)
        self.assertIn('portalAdoption',self.state)
        m.bridge_probe.side_effect=None
        self.identities['query']['Id']='foreign-query'
        with self.assertRaisesRegex(self.r.RuntimeFailure,'ADOPTION_DRIFT'):self.adopt()
        self.r.start.assert_not_called()

    def test_selected_portal_must_be_configured_in_the_actual_native_bridge(self):
        self.native['portalManifests']=['0'*64]
        with self.assertRaisesRegex(self.r.RuntimeFailure,'PORTAL_MANIFEST_NOT_CONFIGURED'):self.adopt()
        self.r.start.assert_not_called();self.assertNotIn('portalAdoption',self.state)

    def test_changed_template_cannot_replace_a_previously_sealed_pending_plan(self):
        self.r.start.side_effect=self.r.RuntimeFailure('INTERRUPTED_BEFORE_REPLACEMENT')
        with self.assertRaises(self.r.RuntimeFailure):self.adopt()
        self.r.start.reset_mock();self.r.start.side_effect=self.started
        render=self.r.gateway_config
        def changed(directory,*args,**kwargs):
            render(directory,*args,**kwargs)
            path=directory/'gateway/server.conf'
            self.r.write_private(path,self.r.private(path)+b'\n# changed template\n',replace=True)
        with patch.object(self.r,'gateway_config',side_effect=changed),self.assertRaisesRegex(self.r.RuntimeFailure,'TEMPLATE_DRIFT'):
            self.adopt()
        self.r.start.assert_not_called();self.assertIn('portalAdoption',self.state)

    def test_wrong_input_private_context_digest_and_incomplete_runtime_reject(self):
        for changed in [dict(self.config,extra='field'),dict(self.config,schemaVersion=True),dict(self.config,portalContextSha256='0'*64)]:
            self.config=changed;self.write()
            with self.assertRaises((self.r.RuntimeFailure,ValueError)):self.adopt()
            self.assertNotIn('portalAdoption',self.state)
        self.config.pop('extra',None);self.config['schemaVersion']=1;self.config['portalContextSha256']=self.selected['contextSha256'];self.write()
        self.state['writerEnabled']=False
        with self.assertRaises(self.r.RuntimeFailure):self.adopt()
        self.r.start.assert_not_called()

    def test_asset_read_uses_exact_original_hashes_and_fixed_logs_route(self):
        selected=self.selected;self.state['portalRuntime']=selected;routes=[]
        record=self.r.portal_adapter().verify_prepared(Path(selected['context']))
        class Response(io.BytesIO): status=200
        class Opener:
            def open(inner,url,**kwargs):
                routes.append(url);route=url.split(str(self.state['port']),1)[1]
                name='logs.html' if route=='/dev/logs' else m.urllib.parse.unquote(route[1:])
                return Response((Path(selected['context'])/'public'/name).read_bytes())
        # Restore actual helper after the lifecycle fixture's explicit boundary mock.
        with patch.object(m.urllib.request,'build_opener',return_value=Opener()),patch.object(m.ssl,'create_default_context'):
            function=type(self).actual_served_assets
            self.assertEqual(len(record['files']),function(self.state,self.directory))
        self.assertTrue(any(url.endswith('/dev/logs') for url in routes))
        self.assertFalse(any(url.endswith('/logs.html') for url in routes))

    actual_served_assets=staticmethod(m.served_assets)

    def test_native_binding_requires_ready_exact_shared_runtime(self):
        directory=self.directory/'operator/native';directory.mkdir(mode=0o700)
        settings=self.directory/'operator/native-settings.json'
        browser=self.directory/'operator/browser-config.json'
        self.r.write_private(browser,json.dumps({'portalManifests':[{'file':'private-manifest','sha256':self.selected['manifestSha256']}]}))
        self.r.write_private(settings,json.dumps({'sharedDirectory':str(self.directory),'browserConfig':str(browser)}))
        self.r.write_private(directory/'state.json',json.dumps({'stage':'READY'}))
        with patch.object(m.n,'status',return_value=self.native['status']) as status:
            value=type(self).actual_native_identity(settings,directory,self.directory)
            self.assertEqual(self.native['status'],value['status'])
            status.assert_called_once_with(settings,directory,verify_shared=False)
            with self.assertRaisesRegex(self.r.RuntimeFailure,'SHARED_MISMATCH'):
                type(self).actual_native_identity(settings,directory,self.directory/'foreign')
            self.r.write_private(directory/'state.json',json.dumps({'stage':'STARTING'}),replace=True)
            with self.assertRaisesRegex(self.r.RuntimeFailure,'NOT_READY'):
                type(self).actual_native_identity(settings,directory,self.directory)

    actual_native_identity=staticmethod(m.native_identity)

    def test_cli_safe_failure_and_owned_success(self):
        self.r.save(self.directory,self.state)
        args=['log-portal-adopt.py','--directory',str(self.directory),'--settings',str(self.file)]
        with patch.object(sys,'argv',args),patch.object(self.r,'local_daemon'),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,m.main());self.assertEqual('ADOPTED',json.loads(output.getvalue())['result'])
        with patch.object(sys,'argv',args),patch.object(self.r,'local_daemon',side_effect=ValueError('private detail')),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1,m.main());self.assertNotIn('private detail',output.getvalue())


class BridgeProbeTest(unittest.TestCase):
    def test_exact_owned_probe_terminal_cleanup_and_unknown_running_preservation(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);(directory/'operator').mkdir();state={'project':'dkp-dev-logging','ownerId':'a'*32}
            identity='c'*64;created=[];running=False;removed=[];mode='PASS'
            def docker(*args,**kwargs):
                nonlocal running
                if args[0]=='create':created.append(args);return identity.encode()
                if args[0]=='start':
                    if mode=='UNKNOWN':running=True;raise m.r.RuntimeFailure('OBSERVATION_TIMEOUT')
                    return b'PASS\n' if mode=='PASS' else b'NO\n'
                if args[0]=='rm':removed.append(args[1]);return b''
                raise AssertionError(args)
            def inspect(kind,name):
                args=created[-1];labels=dict(args[i+1].split('=',1) for i,x in enumerate(args) if x=='--label')
                image=next(x for x in args if x.startswith('python:'))
                return {'Id':identity,'Config':{'Image':image,'Labels':labels},'State':{'Running':running,'ExitCode':0}}
            with patch.object(m.r,'docker',side_effect=docker),patch.object(m.r,'inspect',side_effect=inspect):
                self.assertEqual('PASS',m.bridge_probe(state,directory,58480)['result']);self.assertEqual([identity],removed)
                self.assertIn('bridge',created[0]);self.assertNotIn('--mount',created[0]);self.assertNotIn('--publish',created[0])
                mode='FAIL'
                with self.assertRaisesRegex(m.r.RuntimeFailure,'NATIVE_UNREACHABLE'):m.bridge_probe(state,directory,58480)
                mode='UNKNOWN'
                with self.assertRaises(m.r.RuntimeFailure):m.bridge_probe(state,directory,58480)
                self.assertEqual(2,len(removed))
                receipts=[json.loads(path.read_text()) for path in (directory/'operator').glob('*.json')]
                self.assertEqual(1,sum(value['cleanup']=='RETAINED' for value in receipts))
                with self.assertRaises(m.r.RuntimeFailure):m.bridge_probe(state,directory,True)


if __name__=='__main__':unittest.main()
