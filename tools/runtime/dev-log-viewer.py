#!/usr/bin/env python3
"""Private, bounded read adapter for the loopback-only DKP log viewer. No SQL."""
import datetime as dt
import http.client
import http.server
import json
import os
from pathlib import Path
import re
import socketserver
import stat
import threading
import urllib.parse
import uuid

MAX_PAGE = 4 * 1024 * 1024
KEYS = {'buildId', 'runId', 'component', 'minSeverity', 'correlationId', 'traceId', 'from', 'to', 'limit', 'cursor'}
CSP = "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; script-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; style-src 'self' 'unsafe-inline'"


class Rejected(Exception):
    pass


def token_file(path):
    path = Path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_size > 512:
        raise Rejected()
    token = path.read_text(encoding='ascii')
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,512}', token):
        raise Rejected()
    return token


def query_parameters(raw):
    if len(raw.encode()) > 4096:
        raise Rejected()
    try:
        pairs = urllib.parse.parse_qsl(raw, keep_blank_values=True, strict_parsing=True, max_num_fields=10)
        values = dict(pairs)
        if len(values) != len(pairs) or not set(values) <= KEYS:
            raise ValueError()
        for name in ('buildId', 'runId', 'correlationId'):
            if name in values and (not re.fullmatch(r'[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', values[name]) or uuid.UUID(values[name]).int == 0):
                raise ValueError()
        if 'component' in values and not re.fullmatch(r'[a-z][a-z0-9.-]{0,95}', values['component']):
            raise ValueError()
        if 'minSeverity' in values and values['minSeverity'] not in ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'):
            raise ValueError()
        if 'traceId' in values and (not re.fullmatch('[0-9a-f]{32}', values['traceId']) or values['traceId'] == '0' * 32):
            raise ValueError()
        if not re.fullmatch('[0-9]{1,3}', values.get('limit', '50')) or not 1 <= int(values.get('limit', '50')) <= 200:
            raise ValueError()
        if 'cursor' in values and not 1 <= len(values['cursor']) <= 2048:
            raise ValueError()
        start, end = [dt.datetime.fromisoformat(values[name]) for name in ('from', 'to')]
        if start.tzinfo is None or end.tzinfo is None or not dt.timedelta(0) < end-start <= dt.timedelta(hours=24):
            raise ValueError()
        return urllib.parse.urlencode(values)
    except (ValueError, KeyError, TypeError):
        raise Rejected() from None


def read_page(query, token):
    # Fixed outbound adapter: never derive authority/path/headers from the browser.
    connection = http.client.HTTPConnection('query', 18087, timeout=5)
    try:
        connection.request('GET', '/api/v1/dev-logs/events?' + query,
                           headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json'})
        response = connection.getresponse()
        if response.status == 400:
            return 400, {'code': 'INVALID_LOG_QUERY'}
        if response.status != 200:
            return 503, {'code': 'LOG_VIEWER_UNAVAILABLE'}
        payload = response.read(MAX_PAGE + 1)
        if len(payload) > MAX_PAGE:
            raise Rejected()
        page = json.loads(payload)
        if not isinstance(page, dict) or not isinstance(page.get('events'), list) or len(page['events']) > 200 or any(not isinstance(row,dict) or not isinstance(row.get('event'),dict) or not isinstance(row.get('scope'),dict) or not all(isinstance(row.get(key),str) for key in ('producerId','scopeId','receivedAt')) for row in page['events']):
            raise Rejected()
        if 'nextCursor' in page and (not isinstance(page['nextCursor'], str) or not 1 <= len(page['nextCursor']) <= 2048):
            raise Rejected()
        return 200, page
    except (OSError, ValueError, Rejected, http.client.HTTPException, RecursionError):
        return 503, {'code': 'LOG_VIEWER_UNAVAILABLE'}
    finally:
        connection.close()


class HeaderInput:
    def __init__(self, source):
        self.source, self.first, self.total, self.count = source, True, 0, 0

    def readline(self, size=-1):
        maximum = 4096 if self.first else 2048
        value = self.source.readline(min(size, maximum + 1) if size >= 0 else maximum + 1)
        if len(value) > maximum:
            raise Rejected()
        if not self.first:
            self.total += len(value)
            self.count += int(value not in (b'\r\n', b'\n', b''))
            if self.total > 8192 or self.count > 32:
                raise Rejected()
        self.first = False
        return value

    def __getattr__(self, name):
        return getattr(self.source, name)


class ViewerHandler(http.server.BaseHTTPRequestHandler):
    server_version = 'DKP'
    sys_version = ''

    def setup(self):
        super().setup()
        self.connection.settimeout(2)
        self.request_version, self.command, self.requestline = 'HTTP/1.0', '', ''
        self.rfile = HeaderInput(self.rfile)

    def handle(self):
        try:
            super().handle()
        except (Rejected, OSError, ValueError):
            try: self.respond(400, {'code': 'INVALID_LOG_QUERY'})
            except OSError: pass

    def log_message(self, *args):
        pass  # Request/query/header/body data must never enter console diagnostics.

    def respond(self, status, page):
        payload = json.dumps(page, separators=(',', ':'), ensure_ascii=False).encode()
        if len(payload) > MAX_PAGE:
            status, payload = 503, b'{"code":"LOG_VIEWER_UNAVAILABLE"}'
        self.connection.settimeout(2)
        self.close_connection = True
        self.send_response(status)
        for name, value in {'Content-Type': 'application/json', 'Content-Length': str(len(payload)),
                            'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
                            'Referrer-Policy': 'no-referrer', 'Content-Security-Policy': CSP,
                            'Connection': 'close'}.items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(payload)

    def send_error(self, code, message=None, explain=None):
        self.respond(405 if code == 501 else code, {'code': 'LOG_VIEWER_REQUEST_REJECTED'})

    def do_GET(self):
        if self.headers.get_all('Host') != [self.server.authority]:
            return self.respond(400, {'code': 'LOG_VIEWER_REQUEST_REJECTED'})
        origins = self.headers.get_all('Origin')
        if origins is not None and origins != ['https://' + self.server.authority]:
            return self.respond(403, {'code': 'LOG_VIEWER_REQUEST_REJECTED'})
        if self.headers.get_all('Transfer-Encoding') or self.headers.get_all('Content-Length') not in (None, ['0']):
            return self.respond(400, {'code': 'LOG_VIEWER_REQUEST_REJECTED'})
        try:
            target = urllib.parse.urlsplit(self.path)
            if target.scheme or target.netloc or target.fragment or target.path != '/dev/logs/api/v1/events':
                return self.respond(404, {'code': 'LOG_VIEWER_REQUEST_REJECTED'})
            query = query_parameters(target.query)
        except (Rejected, ValueError):
            return self.respond(400, {'code': 'INVALID_LOG_QUERY'})
        status, page = read_page(query, self.server.token)
        self.respond(status, page)


class ViewerServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, authority, token):
        if not re.fullmatch(r'localhost:[0-9]{4,5}', authority) or not 1024 <= int(authority.split(':')[1]) <= 65535:
            raise Rejected()
        self.authority, self.token = authority, token
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(address, ViewerHandler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.settimeout(1)
                request.sendall(b'HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n')
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        pass


def main():
    try:
        token = token_file(os.environ['DKP_LOG_VIEWER_TOKEN_FILE'])
        with ViewerServer(('0.0.0.0', 18088), os.environ['DKP_LOG_VIEWER_AUTHORITY'], token) as server:
            server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except Exception:
        print('LOG_VIEWER_CONFIGURATION_UNAVAILABLE')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
