"""Private viewer adapter boundaries and real bounded HTTP admission."""
import http.client
import importlib.util
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location('viewer',Path(__file__).with_name('dev-log-viewer.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
QUERY='from=2026-11-02T10%3A00%3A00Z&to=2026-11-02T11%3A00%3A00Z'
TOKEN='synthetic-read-token-'+'a'*44


class ViewerTests(unittest.TestCase):
    def test_token_configuration_is_private_regular_bounded_and_never_echoed(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'token';path.write_text(TOKEN);path.chmod(0o600)
            self.assertEqual(TOKEN,m.token_file(path))
            link=Path(directory)/'link';link.symlink_to(path)
            with self.assertRaises(m.Rejected):m.token_file(link)
            path.chmod(0o644)
            with self.assertRaises(m.Rejected):m.token_file(path)
            path.chmod(0o600)
            for invalid in ['',TOKEN+'\n','x'*513,'not a token']:
                path.write_text(invalid)
                with self.assertRaises(m.Rejected):m.token_file(path)

    def test_closed_typed_queries_allow_filters_and_reject_duplicate_or_unbounded_inputs(self):
        ident='11111111-1111-4111-8111-111111111111'
        valid=QUERY+'&buildId='+ident+'&runId='+ident+'&correlationId='+ident+'&component=runtime-tools&minSeverity=FATAL&traceId='+'a'*32+'&limit=200&cursor=opaque'
        self.assertIn('limit=200',m.query_parameters(valid))
        for suffix in ['&unknown=x','&from=duplicate','&component=Wrong','&minSeverity=BAD','&traceId='+'0'*32,'&traceId=bad','&limit=0','&limit=201','&limit=abc','&cursor=','&cursor='+'a'*2049,'&buildId=bad','&buildId=00000000-0000-0000-0000-000000000000','&runId=bad']:
            with self.subTest(suffix=suffix[:50]),self.assertRaises(m.Rejected):m.query_parameters(QUERY+suffix)
        for invalid in ['', 'bad', QUERY.replace('2026-11-02T11','2026-11-04T11'), QUERY.replace('2026-11-02T10','2026-11-02T12'), QUERY.replace('2026-11-02T10%3A00%3A00Z','2026-11-02'), 'x'*4097]:
            with self.assertRaises(m.Rejected):m.query_parameters(invalid)

    def test_fixed_outbound_authority_credential_and_status_mapping_without_redirects(self):
        response=Mock(status=200);response.read.return_value=b'{"events":[]}'
        connection=Mock();connection.getresponse.return_value=response
        with patch.object(m.http.client,'HTTPConnection',return_value=connection) as factory:
            self.assertEqual((200,{'events':[]}),m.read_page(QUERY,TOKEN))
            factory.assert_called_once_with('query',18087,timeout=5)
            connection.request.assert_called_once_with('GET','/api/v1/dev-logs/events?'+QUERY,headers={'Authorization':'Bearer '+TOKEN,'Accept':'application/json'})
            response.read.assert_called_once_with(m.MAX_PAGE+1);connection.close.assert_called_once()
        for status in [400,401,403,302,500]:
            response=Mock(status=status);connection=Mock();connection.getresponse.return_value=response
            with patch.object(m.http.client,'HTTPConnection',return_value=connection):
                result=m.read_page(QUERY,TOKEN)
                self.assertEqual(400 if status==400 else 503,result[0]);response.read.assert_not_called();connection.close.assert_called_once()

    def test_invalid_oversized_or_unavailable_upstream_never_becomes_empty_success(self):
        connection=Mock();response=Mock(status=200);connection.getresponse.return_value=response
        for payload in [b'x'*(m.MAX_PAGE+1),b'invalid',b'[]',b'{}',b'{"events":[1]}',b'{"events":[],"nextCursor":null}',b'{"events":[],"nextCursor":""}',json.dumps({'events':[{}]*201}).encode()]:
            response.read.return_value=payload
            with patch.object(m.http.client,'HTTPConnection',return_value=connection):self.assertEqual(503,m.read_page(QUERY,TOKEN)[0])
        for failure in [OSError('private'),http.client.HTTPException('private'),RecursionError('private')]:
            connection.getresponse.side_effect=failure
            with patch.object(m.http.client,'HTTPConnection',return_value=connection):
                self.assertEqual({'code':'LOG_VIEWER_UNAVAILABLE'},m.read_page(QUERY,TOKEN)[1])
        self.assertNotIn(TOKEN,str(m.read_page.__doc__))
        row={'event':{'context':{'syntheticBound':'x'*8192}},'scope':{'scopeKind':'TENANT'},'producerId':'id','scopeId':'id','receivedAt':'2026-11-02T19:00:00Z'}
        connection.getresponse.side_effect=None;response.read.return_value=json.dumps({'events':[row]*200}).encode()
        with patch.object(m.http.client,'HTTPConnection',return_value=connection):self.assertEqual(200,len(m.read_page(QUERY,TOKEN)[1]['events']))

    def server(self):
        server=m.ViewerServer(('127.0.0.1',0),'localhost:18443',TOKEN)
        worker=threading.Thread(target=server.serve_forever,kwargs={'poll_interval':.01},daemon=True);worker.start()
        self.addCleanup(lambda:(server.shutdown(),server.server_close(),worker.join(2)))
        return server

    def request(self,server,path='/dev/logs/api/v1/events?'+QUERY,headers=None,method='GET'):
        connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=4)
        connection.request(method,path,headers={'Host':'localhost:18443',**(headers or {})})
        response=connection.getresponse();body=response.read();headers=dict(response.getheaders());status=response.status;connection.close()
        return status,headers,body

    def test_actual_anonymous_http_requires_exact_authority_origin_and_readonly_path(self):
        server=self.server()
        with patch.object(m,'read_page',return_value=(200,{'events':[]})) as read:
            status,headers,body=self.request(server)
            self.assertEqual(200,status);self.assertEqual({'events':[]},json.loads(body));self.assertEqual('no-store',headers['Cache-Control'])
            self.assertIn("frame-ancestors 'none'",headers['Content-Security-Policy']);self.assertNotIn(TOKEN,body.decode())
            self.assertEqual(200,self.request(server,headers={'Origin':'https://localhost:18443','Authorization':'browser-secret','Cookie':'browser-secret'})[0])
            self.assertEqual(TOKEN,read.call_args.args[1])
            for headers,status in [({'Host':'evil.invalid'},400),({'Origin':'null'},403),({'Origin':'https://evil.invalid'},403),({'Origin':''},403),({'Content-Length':'1'},400),({'Transfer-Encoding':'chunked'},400)]:
                self.assertEqual(status,self.request(server,headers=headers)[0])
            self.assertEqual(404,self.request(server,path='/other')[0])
            self.assertEqual(400,self.request(server,path='/dev/logs/api/v1/events?bad')[0])
            for method in ['POST','PUT','DELETE','HEAD']:
                self.assertEqual(405,self.request(server,method=method)[0])

    def test_real_header_admission_holds_four_slots_before_parsing_and_releases_on_close(self):
        server=self.server();clients=[]
        try:
            for index in range(4):
                client=socket.create_connection(('127.0.0.1',server.server_port),timeout=2);clients.append(client)
                client.sendall(b'GET /dev/logs/api/v1/events HTTP/1.1\r\nHost: localhost:18443\r\n')
            until=time.monotonic()+1
            while server.slots._value and time.monotonic()<until:time.sleep(.01)
            self.assertEqual(0,server.slots._value)
            with patch.object(m.socketserver.ThreadingMixIn,'process_request',side_effect=AssertionError('no extra thread')):
                self.assertEqual(503,self.request(server)[0])
        finally:
            for client in clients:client.close()
        until=time.monotonic()+2
        while server.slots._value!=4 and time.monotonic()<until:time.sleep(.01)
        self.assertEqual(4,server.slots._value)
        with patch.object(m,'read_page',return_value=(200,{'events':[]})):self.assertEqual(200,self.request(server)[0])

    def test_raw_duplicate_host_header_count_line_and_total_bounds_are_rejected(self):
        server=self.server()
        for raw in [b'GET /'+b'x'*4096+b' HTTP/1.1\r\n\r\n', b'GET / HTTP/1.1\r\nHost: localhost:18443\r\nX: '+b'x'*2048+b'\r\n\r\n',b'GET / HTTP/1.1\r\n'+b'X: y\r\n'*33+b'\r\n',b'GET / HTTP/1.1\r\n'+(b'X: '+b'x'*1800+b'\r\n')*5+b'\r\n',b'GET /dev/logs/api/v1/events?'+QUERY.encode()+b' HTTP/1.1\r\nHost: localhost:18443\r\nHost: localhost:18443\r\n\r\n']:
            with socket.create_connection(('127.0.0.1',server.server_port),timeout=3) as client:
                client.sendall(raw);response=client.recv(4096);self.assertIn(b' 400 ',response)

    def test_main_configuration_fails_with_fixed_safe_code(self):
        with patch.dict(os.environ,{},clear=True),patch('builtins.print') as output:
            self.assertEqual(1,m.main());output.assert_called_once_with('LOG_VIEWER_CONFIGURATION_UNAVAILABLE')
        with self.assertRaises(m.Rejected):m.ViewerServer(('127.0.0.1',0),'evil.invalid:18443',TOKEN)


if __name__=='__main__':unittest.main()
