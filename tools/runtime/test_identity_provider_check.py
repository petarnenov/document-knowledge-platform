import importlib.util
import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import urllib.error
import urllib.request

SPEC = importlib.util.spec_from_file_location('provider_check', Path(__file__).with_name('identity-provider-check.py'))
provider = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(provider)


class Response:
    def __init__(self, status, body=b'', headers=None):
        self.status, self.body, self.headers = status, body, headers or {}

    def read(self, limit):
        return self.body[:limit]


class ProviderCheckTest(unittest.TestCase):
    def test_fixed_https_destination_and_callback_capture(self):
        provider.require_provider(provider.ISSUER)
        for url in ['http://idp.localhost:18444/', 'https://evil.test:18444/',
                    'https://idp.localhost/', 'https://user@idp.localhost:18444/']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                provider.require_provider(url)
        request = urllib.request.Request(provider.ISSUER)
        redirect = provider.ProviderRedirects()
        self.assertIsNone(redirect.redirect_request(request, None, 302, '', {}, provider.CALLBACK + '?code=synthetic'))
        with self.assertRaises(ValueError):
            redirect.redirect_request(request, None, 302, '', {}, 'https://evil.test/')
        self.assertEqual(provider.ISSUER, redirect.redirect_request(request, None, 302, '', {}, provider.ISSUER).full_url)

    def test_form_extracts_only_hidden_login_fields(self):
        form = provider.LoginForm()
        form.feed('<input type="hidden" name="outside" value="ignored"><form id="kc-form-login" action="https://idp.localhost:18444/login"><input type="hidden" name="credentialId" value="synthetic"><input name="username" value="ignored"></form><input type="hidden" name="after" value="ignored">')
        self.assertEqual({'credentialId': 'synthetic'}, form.values)
        self.assertEqual('https://idp.localhost:18444/login', form.action)

    def test_credential_file_rejects_symlink_and_public_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            values = root / 'runtime.env'
            values.write_text('NAME=synthetic\n')
            with patch.object(provider, 'SECURITY', root):
                values.chmod(0o644)
                with self.assertRaises(ValueError):
                    provider.read_local_values()
                values.chmod(0o600)
                self.assertEqual({'NAME': 'synthetic'}, provider.read_local_values())
                values.rename(root / 'original')
                values.symlink_to(root / 'original')
                with self.assertRaises(ValueError):
                    provider.read_local_values()

    def test_tls_connection_preserves_verified_hostname(self):
        context = MagicMock()
        raw = MagicMock()
        connection = provider.LocalConnection('idp.localhost', 18444, context=context)
        with patch.object(provider.socket, 'create_connection', return_value=raw):
            connection.connect()
        context.wrap_socket.assert_called_once_with(raw, server_hostname='idp.localhost')
        with self.assertRaises(ValueError):
            provider.LocalConnection('evil.test', 18444, context=context).connect()
        handler = provider.TrustedLocalHTTPS(context=context)
        with patch.object(handler, 'do_open', return_value='verified'):
            self.assertEqual('verified', handler.https_open(urllib.request.Request(provider.ISSUER)))

    def test_explicit_subject_is_verified_not_invented(self):
        provider.verify_subject('header.' + base64.urlsafe_b64encode(json.dumps({'iss': provider.ISSUER, 'sub': 'expected'}).encode()).decode() + '.signature', 'expected')
        for token in ['invalid', 'header.e30.signature']:
            with self.assertRaises(AssertionError):
                provider.verify_subject(token, 'expected')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            realm = root / 'dkp-realm.json'
            realm.write_text(json.dumps({'users': [{'username': 'dkp-tester', 'id': 'expected'}]}))
            realm.chmod(0o600)
            with patch.object(provider, 'SECURITY', root):
                self.assertEqual('expected', provider.read_subject())
                realm.chmod(0o644)
                with self.assertRaises(ValueError):
                    provider.read_subject()

    def test_http_rejections_remain_inspectable(self):
        opener = MagicMock()
        error = urllib.error.HTTPError(provider.ISSUER, 403, 'denied', {}, None)
        opener.open.side_effect = error
        self.assertIs(error, provider.open_response(opener, provider.ISSUER))

    def fixture(self):
        return [
            Response(200, b'<form id="kc-form-login" action="https://idp.localhost:18444/login"></form>'),
            Response(302, headers={'Location': provider.CALLBACK + '?state=synthetic&code=synthetic-code'}),
            Response(200, json.dumps({'access_token': 'header.' + base64.urlsafe_b64encode(json.dumps({'iss': provider.ISSUER, 'sub': 'synthetic-subject'}).encode()).decode().rstrip('=') + '.signature'}).encode()),
            Response(200, b'{"username":"dkp-tester","attributes":{}}'),
            Response(403),
            Response(200, b'{"access_token":"synthetic-admin-token"}'),
            Response(200, b'[{"id":"synthetic-subject","attributes":{"dkp_tenants":["tenant-a","tenant-b"]}}]'),
        ]

    def verify(self, responses):
        opener = MagicMock()
        opener.open.side_effect = responses
        values = {'DKP_TEST_USERNAME': 'synthetic', 'DKP_TEST_PASSWORD': 'synthetic',
                  'OIDC_CLIENT_SECRET': 'synthetic', 'KC_BOOTSTRAP_ADMIN_USERNAME': 'synthetic',
                  'KC_BOOTSTRAP_ADMIN_PASSWORD': 'synthetic'}
        with patch.object(provider, 'read_local_values', return_value=values), \
             patch.object(provider, 'read_subject', return_value='synthetic-subject'), \
             patch.object(provider.ssl, 'create_default_context'), \
             patch.object(provider.urllib.request, 'build_opener', return_value=opener), \
             patch.object(provider.secrets, 'token_urlsafe', return_value='synthetic'):
            return provider.verify()

    def test_end_to_end_fixture_requires_authoritative_unchanged_membership(self):
        self.assertTrue(self.verify(self.fixture()))
        cases = [(0, Response(503)), (1, Response(400)),
                 (1, Response(302, headers={'Location': 'https://evil.test/'})),
                 (1, Response(302, headers={'Location': provider.CALLBACK + '?state=forged&code=test'})),
                 (2, Response(401)), (3, Response(403)), (4, Response(500)),
                 (5, Response(401)), (6, Response(503)),
                 (6, Response(200, b'[{"id":"synthetic-subject","attributes":{"dkp_tenants":["tenant-forged"]}}]'))]
        for index, response in cases:
            with self.subTest(index=index, status=response.status):
                fixture = self.fixture()
                fixture[index] = response
                with self.assertRaises(AssertionError):
                    self.verify(fixture)


if __name__ == '__main__':
    unittest.main()
