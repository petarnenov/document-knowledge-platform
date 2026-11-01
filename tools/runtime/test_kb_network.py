import importlib.util
import ipaddress
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('kb_network', Path(__file__).with_name('kb-network.py'))
net = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(net)


class NetworkTest(unittest.TestCase):
    project = 'dkp-bp04-' + 'a' * 12

    def plan(self):
        return net.allocate({'default': {}, 'kb-data': {'internal': True}}, self.project, [])

    def test_deterministic_nonoverlapping_private_plan_preserves_distinct_networks(self):
        plan = self.plan()
        self.assertEqual(plan, self.plan())
        self.assertEqual(list(plan), ['default', 'kb-data'])
        values = list(net.validate_plan(plan).values())
        self.assertFalse(values[0].overlaps(values[1]))
        self.assertTrue(all(p.subnet_of(net.POOL) and p.prefixlen == 28 for p in values))
        self.assertNotEqual(plan, net.allocate({'default': {}, 'kb-data': {}}, 'dkp-bp04-' + 'b' * 12, []))

    def test_docker_and_host_conflicts_are_skipped_and_full_pool_fails_closed(self):
        first = list(net.validate_plan(self.plan()).values())
        plan = net.allocate({'default': {}, 'kb-data': {}}, self.project, first)
        self.assertTrue(all(not a.overlaps(b) for a in net.validate_plan(plan).values() for b in first))
        net.assert_available(plan, first)
        with self.assertRaisesRegex(ValueError, 'now conflict'): net.assert_available(plan, [net.POOL])
        with self.assertRaisesRegex(ValueError, 'No nonconflicting'): net.allocate({'default': {}}, self.project, [ipaddress.ip_network('10.0.0.0/8')])

    def test_no_external_custom_driver_existing_ipam_or_arbitrary_network_names(self):
        for config in [{'external': True}, {'driver': 'host'}, {'ipam': {'driver': 'default'}}, {'enable_ipv6': True}, {'name': 'foreign'}, None]:
            with self.subTest(config=config), self.assertRaises(ValueError): net.allocate({'default': config}, self.project, [])
        for networks, project in [({}, self.project), ({'bad_name': {}}, self.project), ({'default': {}}, 'foreign'), ({str(n): {} for n in range(33)}, self.project), ([], self.project)]:
            with self.subTest(networks=networks), self.assertRaises(ValueError): net.allocate(networks, project, [])

    def test_closed_plan_rejects_widening_duplicate_and_changed_gateway(self):
        plan = self.plan()
        for bad in [None, {}, {'x': {}}, {'x': {'driver': 'other', 'config': []}}, {'x': {'driver': 'default', 'config': [{'subnet': '10.240.0.0/24', 'gateway': '10.240.0.1'}]}}, {'x': {'driver': 'default', 'config': [{'subnet': '10.240.0.0/28', 'gateway': '10.240.0.2'}]}}, {'x': {'driver': 'default', 'config': [{'subnet': '10.241.0.0/28', 'gateway': '10.241.0.1'}]}}]:
            with self.subTest(bad=bad), self.assertRaises((ValueError, TypeError)): net.validate_plan(bad)
        entry = plan['default']
        with self.assertRaises(ValueError): net.validate_plan({'a': entry, 'b': entry})
        entry['config'][0]['ip_range'] = entry['config'][0]['subnet']
        with self.assertRaises(ValueError): net.validate_plan(plan)

    def test_actual_network_requires_exact_subnet_gateway_driver_and_no_extra_range(self):
        plan = self.plan()
        entry = plan['default']['config'][0]
        item = {'Driver': 'bridge', 'EnableIPv6': False, 'IPAM': {'Driver': 'default', 'Config': [{'Subnet': entry['subnet'], 'Gateway': entry['gateway']}]}}
        net.attest(plan, 'default', item)
        for field, value in [('Driver', 'host'), ('EnableIPv6', True), ('IPAM', {}), ('IPAM', {'Config': []}), ('IPAM', {'Config': [{'Subnet': entry['subnet'], 'Gateway': '10.240.0.2'}]}), ('IPAM', {'Config': [{'Subnet': entry['subnet'], 'Gateway': entry['gateway'], 'IPRange': entry['subnet']}]})]:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError): net.attest(plan, 'default', dict(item, **{field: value}))
        with self.assertRaises(ValueError): net.attest(plan, 'unknown', item)

    def test_darwin_abbreviated_networks_host_routes_and_defaults(self):
        text = 'Routing tables\n\nInternet:\nDestination Gateway Flags Netif Expire\ndefault 192.168.0.1 UGSc en0\n10.8/24 10.8.0.32 UGSc utun5\n192.168.0 link#7 UCS en0\n127 link#1 UCS lo0\n10.240.0.19/32 link#1 UH lo0\n'
        self.assertEqual([str(v) for v in net.darwin_routes(text)], ['10.8.0.0/24', '192.168.0.0/24', '127.0.0.0/8', '10.240.0.19/32'])
        for bad in ['', 'Destination Gateway Flags\n', 'Destination Gateway Flags\nwrong\n', 'Destination Gateway Flags\n10.foo link UCS\n', 'Destination Gateway Flags\n10/33 link UCS\n']:
            with self.subTest(bad=bad), self.assertRaises(ValueError): net.darwin_routes(bad)
        with patch.object(net.platform, 'system', return_value='Darwin'), patch.object(net, 'capture', return_value=text): self.assertEqual(len(net.host_routes()), 4)

    def test_linux_all_tables_specific_routes_and_unsupported_or_unknown_data(self):
        routes = [{'dst': 'default'}, {'dst': '10.240.0.0/20'}, {'dst': '127.0.0.1'}]
        with patch.object(net.platform, 'system', return_value='Linux'), patch.object(net, 'capture', return_value=json.dumps(routes)) as capture:
            self.assertEqual([str(v) for v in net.host_routes()], ['10.240.0.0/20', '127.0.0.1/32'])
            self.assertEqual(capture.call_args.args[0], ['ip', '-j', '-4', 'route', 'show', 'table', 'all'])
        for bad in ['{}', '[]', '[{}]', '[{"dst":123}]', '[{"dst":"invalid"}]', 'not-json']:
            with self.subTest(bad=bad), patch.object(net.platform, 'system', return_value='Linux'), patch.object(net, 'capture', return_value=bad), self.assertRaises(ValueError): net.host_routes()
        with patch.object(net.platform, 'system', return_value='Other'), self.assertRaises(ValueError): net.host_routes()

    def test_docker_bounded_full_inventory_filters_only_ipv6_and_checks_completeness(self):
        identities = ['a' * 64, 'b' * 64]
        configs = '\n'.join([json.dumps([{'Subnet': '172.17.0.0/16'}, {'Subnet': 'fd00::/64'}]), 'null'])
        with patch.object(net, 'capture', side_effect=['\n'.join(identities), configs]) as capture:
            self.assertEqual(net.docker_subnets(), [ipaddress.ip_network('172.17.0.0/16')])
            self.assertEqual(capture.call_args.args[0][-2:], identities)
        with patch.object(net, 'capture', return_value=''): self.assertEqual(net.docker_subnets(), [])
        for values in [['bad'], [identities[0] + '\n' + identities[0]], [identities[0], ''], [identities[0], '{}'], [identities[0], '[{}]'], [identities[0], '[{"Subnet":"not-cidr"}]']]:
            with self.subTest(values=values), patch.object(net, 'capture', side_effect=values), self.assertRaises(ValueError): net.docker_subnets()

    def test_subprocess_inventory_is_bounded_and_does_not_relay_errors(self):
        result = subprocess.CompletedProcess(['synthetic'], 0, stdout='safe', stderr='')
        with patch.object(net.subprocess, 'run', return_value=result) as run:
            self.assertEqual(net.capture(['synthetic']), 'safe'); self.assertEqual(run.call_args.kwargs['timeout'], 10)
        for failure in [OSError('sensitive'), subprocess.TimeoutExpired('sensitive', 10), subprocess.CalledProcessError(1, 'sensitive')]:
            with patch.object(net.subprocess, 'run', side_effect=failure), self.assertRaisesRegex(ValueError, '^Cannot establish current address conflicts$'): net.capture(['synthetic'])
        result.stdout = 'x' * (2 * 1024 * 1024 + 1)
        with patch.object(net.subprocess, 'run', return_value=result), self.assertRaises(ValueError): net.capture(['synthetic'])


if __name__ == '__main__':
    unittest.main()
