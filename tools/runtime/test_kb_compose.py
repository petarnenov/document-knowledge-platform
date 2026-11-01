"""Exercise actual Compose merge semantics; no daemon, credentials or providers."""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
ENVIRONMENT = {name: 'synthetic-unit-only' for name in (
    'OIDC_CLIENT_SECRET', 'KC_BOOTSTRAP_ADMIN_USERNAME', 'KC_BOOTSTRAP_ADMIN_PASSWORD',
    'DKP_DB_ADMIN_PASSWORD', 'DKP_DM_MIGRATOR_PASSWORD', 'DKP_DM_RUNTIME_PASSWORD',
    'DKP_KB_ADMIN_PASSWORD', 'DKP_KB_MIGRATOR_PASSWORD', 'DKP_KB_RUNTIME_PASSWORD')}
ENVIRONMENT.update(DKP_LOCAL_UID='10001', DKP_LOCAL_GID='10001',
                   DKP_KB_SECURITY_DIRECTORY='/synthetic-private-kb-unit')


def configuration(overrides=None):
    env = dict(os.environ, **ENVIRONMENT)
    env.update(overrides or {})
    result = subprocess.run(['docker', 'compose', '--env-file', '/dev/null',
                             '-f', str(ROOT / 'infra/docker-compose/compose.yaml'),
                             '-f', str(ROOT / 'infra/docker-compose/compose-kb.yaml'),
                             'config', '--format', 'json'], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if result.returncode:
        raise ValueError('Closed KB Compose configuration failed')
    return json.loads(result.stdout)


class KnowledgeComposeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = configuration()
        cls.services = cls.config['services']

    def test_isolated_ports_and_no_database_or_sharing_proxy_exposure(self):
        self.assertEqual('dkp-bp04', self.config['name'])
        for service, port in (('admin-portal', '20443'), ('keycloak', '20444'),
                              ('document-management', '20081'), ('knowledge-base', '20082')):
            ports = self.services[service]['ports']
            self.assertEqual(1, len(ports))
            self.assertEqual('127.0.0.1', ports[0]['host_ip'])
            self.assertEqual(port, ports[0]['published'])
        for service in ('kb-postgres', 'dm-postgres', 'dm-sharing', 'kb-migrate', 'knowledge-search'):
            self.assertFalse(self.services[service].get('ports'))

    def test_no_kb_http_bypass_or_sibling_database_network(self):
        kb = set(self.services['knowledge-base']['networks'])
        dm = set(self.services['document-management']['networks'])
        self.assertEqual(set(), kb & dm)
        self.assertEqual({'kb-data'}, set(self.services['kb-postgres']['networks']))
        self.assertEqual({'kb-data'}, set(self.services['kb-migrate']['networks']))
        self.assertEqual({'kb-sharing', 'dm-sharing'}, set(self.services['dm-sharing']['networks']))
        self.assertNotIn('dm-data', kb)
        self.assertNotIn('kb-data', dm)
        for name in kb | dm | {'kb-data'}:
            self.assertTrue(self.config['networks'][name]['internal'])

    def test_runtime_migrator_admin_secrets_never_cross_process_boundary(self):
        kb = self.services['knowledge-base']['environment']
        self.assertEqual('dkp_kb_runtime', kb['DKP_KB_JDBC_USERNAME'])
        self.assertIn('/knowledge_base?', kb['DKP_KB_JDBC_URL'])
        self.assertEqual({'DKP_KB_JDBC_PASSWORD'}, {name for name in kb if 'PASSWORD' in name})
        migrator = self.services['kb-migrate']['environment']
        self.assertEqual({'DKP_KB_MIGRATION_URL', 'DKP_KB_MIGRATION_USERNAME', 'DKP_KB_MIGRATION_PASSWORD'}, set(migrator))
        self.assertEqual('dkp_kb_migrator', migrator['DKP_KB_MIGRATION_USERNAME'])
        self.assertEqual('service_completed_successfully', self.services['knowledge-base']['depends_on']['kb-migrate']['condition'])
        self.assertEqual('dkp_kb_local_admin', self.services['kb-postgres']['environment']['POSTGRES_USER'])
        self.assertNotIn('DKP_KB_ADMIN_PASSWORD', kb)
        self.assertFalse(self.services['kb-migrate'].get('volumes'))

    def test_jdbc_urls_preserve_java_owned_timeouts_and_cannot_take_ambient_overrides(self):
        expected = 'jdbc:postgresql://kb-postgres:5432/knowledge_base?currentSchema=kb'
        for profile in (self.config, configuration({
                'DKP_KB_JDBC_URL': 'jdbc:postgresql://attacker/other?currentSchema=public&socketTimeout=99999',
                'DKP_KB_MIGRATION_URL': 'jdbc:postgresql://attacker/other?options=-c%20statement_timeout=0'})):
            self.assertEqual(expected, profile['services']['knowledge-base']['environment']['DKP_KB_JDBC_URL'])
            self.assertEqual(expected, profile['services']['kb-migrate']['environment']['DKP_KB_MIGRATION_URL'])
        for name in ('kb-migrate', 'knowledge-base'):
            environment = self.config['services'][name]['environment']
            self.assertFalse(any(key.startswith('FLYWAY_') for key in environment))

    def test_strict_https_app_only_readonly_authority(self):
        kb = self.services['knowledge-base']
        self.assertEqual('https://dm-sharing:8443', kb['environment']['DKP_KB_DM_BASE_URI'])
        mounts = kb['volumes']
        self.assertTrue(all(mount['read_only'] for mount in mounts))
        targets = {mount['target'] for mount in mounts}
        self.assertEqual({'/run/dkp/kb-authority', '/run/dkp/truststore.p12', '/run/dkp/java-security.options'}, targets)
        self.assertFalse(any('dm-proxy' in mount['source'] or 'dm-ca' in mount['source'] for mount in mounts))
        self.assertEqual({'/run/dkp/dm-tls/server.crt', '/run/dkp/dm-tls/server.key'}, {m['target'] for m in self.services['dm-sharing']['volumes']})
        self.assertTrue(all(m['read_only'] for m in self.services['dm-sharing']['volumes']))

    def test_exact_identity_origins_and_no_old_runtime_paths(self):
        for name in ('document-management', 'knowledge-base', 'knowledge-search', 'browser-bff'):
            env = self.services[name]['environment']
            self.assertEqual('https://idp.localhost:20444/realms/dkp', env['OIDC_ISSUER'])
        self.assertEqual('https://localhost:20443/oauth/callback/keycloak', self.services['browser-bff']['environment']['OIDC_CALLBACK_URI'])
        self.assertEqual('20444', self.services['keycloak']['environment']['KC_HTTPS_PORT'])
        for service in self.services.values():
            for mount in service.get('volumes', []):
                if mount['type'] == 'bind' and mount['target'].startswith('/run/dkp'):
                    self.assertTrue(mount['source'].startswith('/synthetic-private-kb-unit/'))

    def test_durability_and_nonroot_readonly_capabilities(self):
        postgres = self.services['kb-postgres']
        for setting in ('fsync=on', 'synchronous_commit=on', 'full_page_writes=on', 'password_encryption=scram-sha-256'):
            self.assertIn(setting, postgres['command'])
        self.assertEqual('kb-postgres-data', postgres['volumes'][0]['source'])
        for name in ('knowledge-base', 'kb-postgres', 'kb-migrate', 'dm-sharing'):
            service = self.services[name]
            self.assertTrue(service['read_only'])
            self.assertNotEqual('0', service['user'].split(':')[0])
            self.assertIn('ALL', service['cap_drop'])
            self.assertIn('no-new-privileges:true', service['security_opt'])

    def test_missing_required_secrets_fail_without_default_authority(self):
        for name in ('DKP_KB_ADMIN_PASSWORD', 'DKP_KB_RUNTIME_PASSWORD', 'DKP_KB_MIGRATOR_PASSWORD', 'DKP_KB_SECURITY_DIRECTORY'):
            with self.assertRaises(ValueError):
                configuration({name: ''})

    def test_reviewed_pins_schema_and_proxy_finite_guards(self):
        infra = ROOT / 'infra/docker-compose'
        self.assertIn('postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4', (infra / 'kb-postgres.Dockerfile').read_text())
        sql = (infra / 'kb-postgres-init.sql').read_text()
        self.assertIn('CREATE DATABASE knowledge_base OWNER dkp_kb_local_admin', sql)
        self.assertIn('CREATE SCHEMA kb AUTHORIZATION dkp_kb_migrator', sql)
        self.assertIn('REVOKE ALL ON DATABASE knowledge_base FROM PUBLIC', sql)
        self.assertNotIn('GRANT ALL', sql)
        self.assertNotIn('CREATE TABLE', sql)
        proxy = (infra / 'kb-dm-sharing.conf').read_text()
        for guard in ('knowledge-share-(assessments|eligibility)$', '$request_method != POST', 'client_max_body_size 16k',
                      'proxy_pass_request_headers off', 'Authorization $http_authorization', 'X-DKP-Tenant $http_x_dkp_tenant',
                      'proxy_next_upstream off', 'proxy_max_temp_file_size 0', 'access_log off', 'error_log /dev/null'):
            self.assertIn(guard, proxy)
        self.assertNotIn('proxy_set_header Cookie', proxy)
        self.assertNotIn('ssl_verify_client off', proxy)
        self.assertIn('location = /health/readiness', proxy)
        self.assertIn('proxy_pass_request_body off', proxy)
        migration = (infra / 'kb-migrator.Dockerfile').read_text()
        self.assertIn('org.documentknowledge.knowledgebase.bootstrap.KnowledgeMigrationMain', migration)
        self.assertNotIn('Micronaut', migration)
        check = (infra / 'kb-database-check.sql').read_text()
        self.assertIn("tablename<>'flyway_schema_history')=13", check)
        self.assertIn("NOT has_table_privilege('dkp_kb_runtime','kb.audience_policies','UPDATE')", check)
        self.assertIn("NOT has_database_privilege('dkp_kb_runtime','postgres','CONNECT')", check)


if __name__ == '__main__':
    unittest.main()
