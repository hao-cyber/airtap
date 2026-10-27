import base64
import importlib.util
import json
import os
from pathlib import Path
import ssl
import tempfile
import unittest
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location('reports', Path(__file__).parents[1]/'reports.py')
r = importlib.util.module_from_spec(spec); spec.loader.exec_module(r)
# Reuse only the ephemeral TLS/CONNECT harness, not its test cases. All requests
# below enter reports.request; no production credentials or external hosts.
tls_spec = importlib.util.spec_from_file_location('reports_tls_fixture', Path(__file__).with_name('test_cli_tls.py'))
tls = importlib.util.module_from_spec(tls_spec); tls_spec.loader.exec_module(tls)

class ReportsTests(unittest.TestCase):
    def test_private_roundtrip_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)/'private.json'
            r.write_new(path, {'origin':'https://reports.example.com','token':'a'*64})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(r.config(path)['origin'], 'https://reports.example.com')
            with self.assertRaises(FileExistsError): r.write_new(path, {})
            path.chmod(0o644)
            with self.assertRaises(ValueError): r.config(path)
    def test_invalid_output_prevents_mutation(self):
        with tempfile.TemporaryDirectory() as temp:
            cfg=Path(temp)/'config.json';r.write_new(cfg,{'origin':'https://reports.example.com','token':'a'*64})
            with patch('sys.argv',['reports.py','--config',str(cfg),'delete','f9c916a1-2ab0-4a21-896f-1c30b9605ced','--output',str(Path(temp)/'missing'/'out.json')]), patch.object(r,'request') as called:
                with self.assertRaises(FileNotFoundError):r.main()
                called.assert_not_called()
    def test_timeout_after_headers_is_unknown_outcome(self):
        transport=r.load_transport();connection=MagicMock();connection.getresponse.return_value.status=200
        connection.getresponse.return_value.read.side_effect=TimeoutError()
        with patch.object(r,'load_transport',return_value=transport), patch.object(transport,'connection_for_url',return_value=connection):
            result=r.request({'origin':'https://reports.example.com','token':'c'*64},'DELETE','/v1/admin/reports/id')
        self.assertEqual(result['error'],'outcome_unknown')
        connection.close.assert_called_once()
    def test_missing_shared_policy_fails_before_network(self):
        with patch.object(r.importlib.util,'spec_from_file_location',side_effect=FileNotFoundError()), patch.object(r.http.client.HTTPSConnection,'connect') as connect:
            result=r.request({'origin':'https://reports.example.com','token':'c'*64},'DELETE','/v1/admin/reports/id')
        self.assertEqual(result['error'],'tls_policy_unavailable')
        self.assertEqual(result['outcome'],'not_dispatched')
        connect.assert_not_called()
    def test_certificate_error_after_http_attempt_is_unknown(self):
        transport=r.load_transport();connection=MagicMock()
        connection.getresponse.side_effect=ssl.SSLCertVerificationError('private exception text')
        with patch.object(r,'load_transport',return_value=transport), patch.object(transport,'connection_for_url',return_value=connection):
            result=r.request({'origin':'https://reports.example.com','token':'c'*64},'DELETE','/v1/admin/reports/id')
        self.assertEqual(result['outcome'],'unknown')
        self.assertNotIn('private',json.dumps(result))
    def test_direct_caller_cannot_choose_cleartext(self):
        with patch.object(r,'load_transport') as load:
            with self.assertRaises(ValueError):
                r.request({'origin':'http://reports.example.com','token':'c'*64},'GET','/')
        load.assert_not_called()
    def test_invalid_origins(self):
        for value in ['http://example.com', 'https://a:b@example.com', 'https://example.com/path', 'https://example.com?token=x', 'https://example.com/#x', 'https://example.com:444', 'https://example.com\\other']:
            with self.subTest(value=value), self.assertRaises(ValueError): r.origin(value)

class ReportsHTTP(tls.QuietHTTP):
    def do_GET(self):
        body=self.rfile.read(int(self.headers.get('Content-Length',0)))
        self.server.received.append({'method':self.command,'path':self.path,'headers':dict(self.headers.items()),'body':body})
        if self.path == '/redirect':
            self.send_response(302);self.send_header('Location','/private-target');self.end_headers()
            self.wfile.write(b'{"secret":"private report body"}')
        elif self.path == '/error':
            self.send_response(500);self.end_headers();self.wfile.write(b'private report body')
        else:
            self.send_response(200);self.end_headers();self.wfile.write(b'{"reports":[]}')
    do_POST=do_GET
    do_PATCH=do_GET
    do_DELETE=do_GET


class ReportsTLS(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        tls.LocalTLSAndProxy.setUpClass.__func__(cls)
    @classmethod
    def tearDownClass(cls):
        tls.LocalTLSAndProxy.tearDownClass.__func__(cls)
    def setUp(self):
        tls.LocalTLSAndProxy.setUp(self)
        self.http.RequestHandlerClass=ReportsHTTP
        self.transport=r.load_transport()
    def tearDown(self):
        tls.LocalTLSAndProxy.tearDown(self)
    def request(self, method='POST', path='/v1/admin/reports/query', body=None, *, proxy=False, blocked=False, unavailable=False, hostname='localhost', trusted=True):
        if proxy:
            os.environ['https_proxy']='http://test-user:test-proxy-password@127.0.0.1:'+str(self.proxy.server_address[1])
        cfg={'origin':'https://'+hostname,'token':'b'*64}
        # Only the ephemeral target port and test CA differ. Production origin
        # validation, TLS chain/hostname checks, proxy logic and HTTP call remain.
        factory=self.transport.connection_for_url
        def local_connection(parsed, timeout):
            return factory(parsed._replace(netloc=hostname+':'+str(self.http.server_port)),timeout)
        def context():
            return self.real_context(cafile=str(self.ca)) if trusted else self.real_context()
        keys=self.transport.COMPROMISED_SPKI_SHA256 | ({self.leaf_spki} if blocked else set())
        with patch.object(r,'load_transport',return_value=self.transport), patch.object(self.transport,'connection_for_url',local_connection), patch.object(self.transport.ssl,'create_default_context',context), patch.object(self.transport,'COMPROMISED_SPKI_SHA256',keys):
            if unavailable:
                with patch.object(self.transport,'verify_peer_chain',side_effect=self.transport.TLSVerificationError('tls_verified_chain_unavailable')):
                    return r.request(cfg,method,path,body)
            return r.request(cfg,method,path,body)
    def test_real_https_crud_preserves_methods_json_and_bearer(self):
        for method,body in [('GET',None),('POST',{'limit':2}),('PATCH',{'status':'reviewed','note':'中文'}),('DELETE',None)]:
            with self.subTest(method=method):
                result=self.request(method,body=body)
                self.assertEqual(result,{'ok':True,'http_status':200,'data':{'reports':[]}})
                seen=self.http.received[-1]
                self.assertEqual(seen['method'],method)
                self.assertEqual(seen['headers']['Authorization'],'Bearer '+'b'*64)
                self.assertEqual(json.loads(seen['body']) if body is not None else seen['body'],body if body is not None else b'')
    def test_real_https_blocked_key_sends_no_authorization_or_body(self):
        result=self.request(body={'note':'private report body'},blocked=True)
        self.assertEqual(result['error'],'tls_compromised_certificate_key')
        self.assertEqual(result['outcome'],'not_dispatched')
        self.assertFalse(result['retry_action'])
        self.assertEqual(self.http.received,[])
    def test_real_connect_blocked_key_sends_only_proxy_credentials(self):
        result=self.request(body={'note':'private report body'},proxy=True,blocked=True)
        self.assertEqual(result['error'],'tls_compromised_certificate_key')
        self.assertEqual(result['outcome'],'not_dispatched')
        self.assertEqual(self.http.received,[])
        self.assertEqual(len(self.proxy.received),1)
        connect=self.proxy.received[0]
        self.assertIn(b'Proxy-Authorization: Basic '+base64.b64encode(b'test-user:test-proxy-password'),connect)
        self.assertNotIn(b'b'*64,connect)
        self.assertNotIn(b'private report body',connect)
    def test_real_connect_success_does_not_forward_proxy_auth(self):
        result=self.request(body={'note':'test'},proxy=True)
        self.assertTrue(result['ok'])
        self.assertNotIn('Proxy-Authorization',self.http.received[0]['headers'])
        self.assertEqual(json.loads(self.http.received[0]['body']),{'note':'test'})
    def test_unavailable_verified_chain_fails_closed_before_request(self):
        result=self.request(body={'note':'private report body'},unavailable=True)
        self.assertEqual(result['error'],'tls_verified_chain_unavailable')
        self.assertEqual(result['outcome'],'not_dispatched')
        self.assertEqual(self.http.received,[])
    def test_system_trust_and_hostname_remain_required(self):
        for settings in [{'trusted':False},{'hostname':'127.0.0.1'},{'proxy':True,'hostname':'wrong.example.invalid'}]:
            with self.subTest(settings=settings):
                result=self.request(body={'note':'private report body'},**settings)
                self.assertEqual(result['error'],'tls_certificate_verification_failed')
                self.assertEqual(result['outcome'],'not_dispatched')
        self.assertEqual(self.http.received,[])
    def test_redirect_and_error_body_do_not_leak(self):
        for path,status in [('/redirect',302),('/error',500)]:
            result=self.request('GET',path)
            self.assertFalse(result['ok']);self.assertEqual(result['http_status'],status)
            self.assertNotIn('private',json.dumps(result));self.assertNotIn('b'*64,json.dumps(result))
        self.assertEqual([entry['path'] for entry in self.http.received],['/redirect','/error'])

if __name__ == '__main__':unittest.main()
