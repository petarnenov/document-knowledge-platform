"""Fixed-route verified HTTPS transport with no DNS/proxy/ambient credentials."""
import hashlib
import http.client
import ipaddress
import socket
import ssl
import threading
import time
import urllib.parse

from .wire import require, matches, BATCH_BYTES


class HttpsTransport:
    def __init__(self, endpoint, producer_token, ca_file, connect_address):
        parsed = urllib.parse.urlsplit(endpoint)
        require(parsed.scheme == 'https' and parsed.hostname and parsed.username is None
            and parsed.password is None and not parsed.query and not parsed.fragment
            and parsed.path == '/api/v1/dev-logs')
        require(matches(producer_token, r'[A-Za-z0-9_-]{32,512}'))
        self._address = ipaddress.ip_address(connect_address)
        self._host, self._port = parsed.hostname, parsed.port or 443
        self._tls = ssl.create_default_context(cafile=ca_file)
        self._token = producer_token
        self._lock = threading.Lock()
        self._active = None
        self._closed = False

    def credential_fingerprint(self):
        return hashlib.sha256(('dkp-log-producer-credential-v1:' + self._token).encode('ascii')).hexdigest()

    def _abort(self):
        with self._lock:
            active = self._active
        if active is not None:
            try:
                active.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def post(self, route, body):
        require(route in ('batches', 'receipts') and type(body) is bytes and 0 < len(body) <= BATCH_BYTES)
        raw = socket.socket(socket.AF_INET6 if self._address.version == 6 else socket.AF_INET, socket.SOCK_STREAM)
        with self._lock:
            if self._closed or self._active is not None:
                raw.close()
                raise OSError('LOG_HTTP_UNAVAILABLE')
            self._active = raw
        deadline = time.monotonic() + 5
        expired = threading.Event()

        def expire():
            expired.set()
            self._abort()

        timer = threading.Timer(5, expire)
        timer.daemon = True
        timer.start()
        connection = None
        try:
            raw.settimeout(1)
            raw.connect((str(self._address), self._port))
            raw.settimeout(max(.001, min(1, deadline - time.monotonic())))
            secured = self._tls.wrap_socket(raw, server_hostname=self._host, do_handshake_on_connect=False)
            with self._lock:
                self._active = secured
                closed = self._closed
            if closed or expired.is_set():
                raise OSError('LOG_HTTP_UNAVAILABLE')
            secured.do_handshake()
            secured.settimeout(max(.001, deadline - time.monotonic()))
            connection = http.client.HTTPSConnection(self._host, self._port, context=self._tls)
            connection.sock = secured
            connection.request('POST', '/api/v1/dev-logs/' + route, body=body,
                headers={'Authorization': 'Bearer ' + self._token, 'Content-Type': 'application/json',
                         'Accept': 'application/json', 'Connection': 'close'})
            response = connection.getresponse()
            if response.getheader('Content-Encoding') is not None:
                raise OSError('LOG_HTTP_UNAVAILABLE')
            if response.status == 200 and response.getheader('Content-Type', '').split(';', 1)[0].strip().lower() != 'application/json':
                raise OSError('LOG_HTTP_UNAVAILABLE')
            data = response.read(BATCH_BYTES + 1)
            if len(data) > BATCH_BYTES or time.monotonic() >= deadline:
                raise OSError('LOG_HTTP_UNAVAILABLE')
            return response.status, data
        except (OSError, ValueError, http.client.HTTPException):
            raise OSError('LOG_HTTP_UNAVAILABLE') from None
        finally:
            timer.cancel()
            timer.join()
            with self._lock:
                active, self._active = self._active, None
            if connection is not None:
                connection.close()
            if active is not None:
                active.close()
            raw.close()

    def close(self):
        with self._lock:
            self._closed = True
        self._abort()
