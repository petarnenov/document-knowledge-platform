#!/usr/bin/env python3
"""Real local IdP fixture: ordinary users cannot edit tenant authority claims."""
import base64
from html.parser import HTMLParser
import hashlib
import http.client
import http.cookiejar
import json
from pathlib import Path
import secrets
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request

ISSUER = 'https://idp.localhost:18444/realms/dkp'
CALLBACK = 'https://localhost:18443/oauth/callback/keycloak'
SECURITY = Path(__file__).resolve().parents[2] / 'local-data/security'


def require_provider(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname != 'idp.localhost' or parsed.port != 18444 or parsed.username or parsed.password:
        raise ValueError('Unapproved identity-provider destination')


class LocalConnection(http.client.HTTPSConnection):
    def connect(self):
        # Map only DNS, retaining the exact TLS hostname and certificate checks.
        if self.host != 'idp.localhost' or self.port != 18444:
            raise ValueError('Unapproved identity-provider connection')
        raw = socket.create_connection(('127.0.0.1', 18444), self.timeout)
        self.sock = self._context.wrap_socket(raw, server_hostname=self.host)


class TrustedLocalHTTPS(urllib.request.HTTPSHandler):
    def https_open(self, request):
        require_provider(request.full_url)
        return self.do_open(LocalConnection, request, context=self._context)


class ProviderRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if f'{parsed.scheme}://{parsed.netloc}{parsed.path}' == CALLBACK:
            return None  # Capture the test code without invoking the application's callback.
        require_provider(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None
        self.values = {}
        self.active = False

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == 'form' and attributes.get('id') == 'kc-form-login':
            self.active = True
            self.action = attributes.get('action')
        if self.active and tag == 'input' and attributes.get('type') == 'hidden' and attributes.get('name'):
            self.values[attributes['name']] = attributes.get('value', '')

    def handle_endtag(self, tag):
        if tag == 'form':
            self.active = False


def read_local_values():
    file = SECURITY / 'runtime.env'
    if file.is_symlink() or file.stat().st_mode & 0o077:
        raise ValueError('Owner-only local identity settings required')
    return dict(line.split('=', 1) for line in file.read_text().splitlines() if '=' in line)


def open_response(opener, request):
    try:
        return opener.open(request, timeout=10)
    except urllib.error.HTTPError as error:
        return error


def verify():
    values = read_local_values()
    context = ssl.create_default_context(cafile=str(SECURITY / 'ca.crt'))
    opener = urllib.request.build_opener(TrustedLocalHTTPS(context=context),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), ProviderRedirects())
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
    state = secrets.token_urlsafe(32)
    query = urllib.parse.urlencode({'client_id': 'dkp-bff', 'response_type': 'code',
        'redirect_uri': CALLBACK, 'scope': 'openid profile', 'state': state,
        'nonce': secrets.token_urlsafe(32), 'code_challenge': challenge, 'code_challenge_method': 'S256'})
    response = open_response(opener, ISSUER + '/protocol/openid-connect/auth?' + query)
    if response.status != 200:
        raise AssertionError('Provider did not present login')
    form = LoginForm()
    form.feed(response.read(256 * 1024).decode())
    require_provider(form.action or '')
    form.values.update(username=values['DKP_TEST_USERNAME'], password=values['DKP_TEST_PASSWORD'])
    response = open_response(opener, urllib.request.Request(form.action,
        data=urllib.parse.urlencode(form.values).encode(), headers={'Content-Type': 'application/x-www-form-urlencoded'}))
    if response.status not in (302, 303):
        raise AssertionError('Provider rejected synthetic authorization-code login')
    location = urllib.parse.urlsplit(response.headers.get('Location', ''))
    if f'{location.scheme}://{location.netloc}{location.path}' != CALLBACK:
        raise AssertionError('Unexpected authorization redirect')
    authorization = urllib.parse.parse_qs(location.query)
    if authorization.get('state') != [state] or len(authorization.get('code', [])) != 1:
        raise AssertionError('Invalid authorization response')
    response = open_response(opener, urllib.request.Request(ISSUER + '/protocol/openid-connect/token',
        data=urllib.parse.urlencode({'grant_type': 'authorization_code', 'client_id': 'dkp-bff',
            'client_secret': values['OIDC_CLIENT_SECRET'], 'redirect_uri': CALLBACK,
            'code': authorization['code'][0], 'code_verifier': verifier}).encode(),
        headers={'Content-Type': 'application/x-www-form-urlencoded'}))
    if response.status != 200:
        raise AssertionError('Provider rejected confidential PKCE exchange')
    token = json.loads(response.read(128 * 1024))['access_token']
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/json', 'Origin': 'https://localhost:18443'}
    account = ISSUER + '/account/'
    response = open_response(opener, urllib.request.Request(account, headers=headers))
    if response.status != 200:
        raise AssertionError('Account profile probe is not authorized; cannot prove tenant-claim immutability')
    profile = json.loads(response.read(128 * 1024))
    profile.setdefault('attributes', {})['dkp_tenants'] = ['tenant-forged']
    response = open_response(opener, urllib.request.Request(account, data=json.dumps(profile).encode(),
        headers={**headers, 'Content-Type': 'application/json'}, method='POST'))
    if response.status not in (200, 204, 400, 403):
        raise AssertionError('Unexpected account mutation response')
    # A hidden field alone proves nothing. Inspect persisted membership through
    # the local administrator, never granting that authority to the test user.
    response = open_response(opener, urllib.request.Request(
        'https://idp.localhost:18444/realms/master/protocol/openid-connect/token',
        data=urllib.parse.urlencode({'grant_type': 'password', 'client_id': 'admin-cli',
            'username': values['KC_BOOTSTRAP_ADMIN_USERNAME'],
            'password': values['KC_BOOTSTRAP_ADMIN_PASSWORD']}).encode()))
    if response.status != 200:
        raise AssertionError('Local administrative verification unavailable')
    administrative_token = json.loads(response.read(128 * 1024))['access_token']
    response = open_response(opener, urllib.request.Request(
        'https://idp.localhost:18444/admin/realms/dkp/users?username=dkp-tester&exact=true',
        headers={'Authorization': 'Bearer ' + administrative_token}))
    if response.status != 200:
        raise AssertionError('Authoritative local user recheck unavailable')
    users = json.loads(response.read(128 * 1024))
    if len(users) != 1 or users[0].get('attributes', {}).get('dkp_tenants') != ['tenant-a', 'tenant-b']:
        raise AssertionError('Ordinary user modified tenant authority')
    return True


if __name__ == '__main__':
    try:
        verify()
        print('Real IdP account mutation rejection PASS; no credentials or tokens emitted.')
    except Exception:
        print('Real IdP account mutation verification failed; no credential-bearing diagnostic emitted.')
        raise SystemExit(1)
