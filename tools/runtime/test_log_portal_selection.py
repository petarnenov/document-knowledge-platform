"""Original portal packaging remains selected across independent runtime updates."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

import test_dev_logging as runtime
import test_log_portal_image as portal

m = runtime.m


class PortalSelectionTest(unittest.TestCase):
    def setUp(self):
        self.runtime = runtime.LoggingRuntimeTests(); self.runtime.setUp(); self.addCleanup(self.runtime.doCleanups)
        self.directory = self.runtime.directory.resolve(); self.state = self.runtime.ready()
        self.state['directory'] = str(self.directory)
        self.portal = portal.PortalImageTest(); self.portal.setUp(); self.addCleanup(self.portal.doCleanups)
        record = portal.m.prepare(self.portal.root, self.portal.receipt, self.portal.output)
        parent = self.directory/'operator/portal-builds'; parent.mkdir(mode=0o700)
        context = parent/record['buildId']; shutil.copytree(self.portal.output, context)
        self.selected = {'context': str(context), 'contextSha256': hashlib.sha256((context/'image-context.json').read_bytes()).hexdigest(),
            'buildId': record['buildId'], 'manifestSha256': record['manifestSha256'],
            'artifactSha256': record['artifact']['sha256'], 'nativeBffPort': 58480}
        self.state['portalRuntime'] = self.selected

    def test_selected_original_context_and_fixed_port_are_verified_without_recompilation(self):
        self.assertEqual(self.selected, m.portal_selection(self.state, self.directory))
        original = copy.deepcopy(self.selected)
        for key, value in [('context', str(self.portal.output)), ('contextSha256', '0'*64),
                ('buildId', '00000000-0000-0000-0000-000000000000'), ('manifestSha256', '0'*64),
                ('artifactSha256', '0'*64), ('nativeBffPort', True), ('nativeBffPort', 18087),
                ('nativeBffPort', '58480')]:
            self.state['portalRuntime'] = {**original, key: value}
            with self.subTest(key=key), self.assertRaises((ValueError,m.RuntimeFailure)):
                m.portal_selection(self.state, self.directory)
        self.state['portalRuntime'] = {**original, 'extra': 'field'}
        with self.assertRaises(m.RuntimeFailure): m.portal_selection(self.state, self.directory)
        self.state['portalRuntime'] = original
        (Path(original['context'])/'public/index.html').write_text('changed')
        with self.assertRaises(ValueError): m.portal_selection(self.state, self.directory)
        del self.state['portalRuntime']
        self.assertIsNone(m.portal_selection(self.state,self.directory))

    def test_build_uses_only_verified_original_context_and_records_distinct_compiler_identity(self):
        import sys
        sys.path.insert(0,str(m.ROOT/'tools/ci')); import verify
        fingerprint = 'e'*64; builds = []
        image = {'Id':'sha256:'+'c'*64, 'Os':'linux', 'Architecture':'amd64', 'Config':{'Labels':m.labels(self.state)}}
        def build(*args, **kwargs):
            self.assertEqual('build',args[0]); self.assertEqual(self.selected['context'],args[-1])
            self.assertEqual(str(Path(self.selected['context'])/'Dockerfile'),args[args.index('--file')+1])
            self.assertIn('dkp.logging.portal-build='+self.selected['buildId'],args)
            self.assertIn('dkp.logging.portal-manifest='+self.selected['manifestSha256'],args)
            self.assertIn('dkp.logging.portal-artifact='+self.selected['artifactSha256'],args)
            self.assertIn('dkp.logging.source='+fingerprint,args)
            self.assertTrue(args[args.index('--tag')+1].endswith('-'+self.selected['buildId']))
            builds.append(args)
        with patch.object(verify,'snapshot',return_value={'fingerprint':fingerprint}), patch.object(m,'docker',side_effect=build), \
                patch.object(m,'exists',return_value=False), patch.object(m,'inspect',return_value=image):
            result=m.build_image(self.state,self.directory,component='gateway')
        self.assertEqual(fingerprint,result['gatewaySourceFingerprint']); self.assertEqual(image['Id'],result['gatewayImageId'])
        self.assertEqual(1,len(builds))
        def changing(*args,**kwargs):
            build(*args,**kwargs); (Path(self.selected['context'])/'public/index.html').write_text('drift during build')
        with patch.object(verify,'snapshot',return_value={'fingerprint':fingerprint}), patch.object(m,'docker',side_effect=changing), \
                patch.object(m,'exists',return_value=False), self.assertRaises(ValueError):
            m.build_image(self.state,self.directory,component='gateway')

    def test_later_gateway_update_preserves_original_assets_and_native_upstream(self):
        services = []
        def compose(state,directory,*args,**kwargs): services.append(args[-1])
        with patch.object(m,'check_resources'),patch.object(m,'assert_schema'),patch.object(m,'build_image',return_value={}), \
                patch.object(m,'compose',side_effect=compose),patch.object(m,'disk'),patch.object(m,'provision'), \
                patch.object(m,'probe'),patch.object(m,'verify_live',return_value={'result':'PASS'}):
            self.assertEqual({'result':'PASS'},m.start(self.state,self.directory,update=True,target='gateway'))
        self.assertEqual(['postgres','gateway'],services)
        self.assertIn('set $bff http://host.docker.internal:58480;', (self.directory/'gateway/server.conf').read_text())
        self.assertEqual(self.selected,m.portal_selection(self.state,self.directory))
        self.assertEqual(self.selected,json.loads((self.directory/'state.json').read_text())['portalRuntime'])

    def test_runtime_status_requires_original_portal_image_labels(self):
        image = {'Config':{'Labels':{**m.labels(self.state),'dkp.logging.source':self.state['sourceFingerprint']}}}
        # Fail on the image label check before probing any unrelated runtime resource.
        with patch.object(m,'check_resources'),patch.object(m,'inspect',return_value=image),self.assertRaisesRegex(m.RuntimeFailure,'PORTAL_IMAGE_MISMATCH'):
            m.verify_live(self.state,self.directory)
        image['Config']['Labels'].update({'dkp.logging.portal-build':self.selected['buildId'],
            'dkp.logging.portal-manifest':self.selected['manifestSha256'],
            'dkp.logging.portal-artifact':self.selected['artifactSha256']})
        with patch.object(m,'check_resources'),patch.object(m,'inspect',return_value=image), \
                patch.object(m,'container',side_effect=lambda state,directory,service:self.runtime.item(service)), \
                patch.object(m,'assert_schema'),patch.object(m,'probe'):
            self.assertEqual('PASS',m.verify_live(self.state,self.directory)['result'])


if __name__ == '__main__': unittest.main()
