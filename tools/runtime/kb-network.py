#!/usr/bin/env python3
"""Bounded, development-only IPAM; never allocates, adopts or deletes Docker resources."""
import hashlib
import ipaddress
import json
import platform
import re
import subprocess

POOL = ipaddress.IPv4Network('10.240.0.0/16')
PREFIX = 28
MAX_NETWORKS = 32


def capture(arguments):
    try:
        result = subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=10)
        if len(result.stdout.encode()) > 2 * 1024 * 1024:
            raise ValueError('Network inventory too large')
        return result.stdout
    except (OSError, subprocess.SubprocessError):
        raise ValueError('Cannot establish current address conflicts') from None


def docker_subnets():
    identifiers = capture(['docker', 'network', 'ls', '-q', '--no-trunc']).split()
    if (len(identifiers) > 256 or len(set(identifiers)) != len(identifiers)
            or any(not re.fullmatch(r'[a-f0-9]{64}', value) for value in identifiers)):
        raise ValueError('Invalid bounded Docker network inventory')
    if not identifiers:
        return []
    lines = capture(['docker', 'network', 'inspect', '--format', '{{json .IPAM.Config}}', *identifiers]).splitlines()
    if len(lines) != len(identifiers):
        raise ValueError('Incomplete Docker address inventory')
    result = []
    for line in lines:
        configs = json.loads(line)
        if configs is None:
            continue
        if not isinstance(configs, list) or len(configs) > 8:
            raise ValueError('Invalid Docker IPAM inventory')
        for config in configs:
            if not isinstance(config, dict) or not isinstance(config.get('Subnet'), str):
                raise ValueError('Missing Docker subnet')
            subnet = ipaddress.ip_network(config['Subnet'], strict=True)
            if subnet.version == 4:
                result.append(subnet)
    return result


def darwin_routes(text):
    result, header, seen = [], False, False
    for line in text.splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[:3] == ['Destination', 'Gateway', 'Flags']:
            header = True
            continue
        if not header:
            continue
        if len(fields) < 3:
            raise ValueError('Unrecognized host route')
        destination = fields[0]
        seen = True
        if destination == 'default':
            continue
        address, *prefix = destination.split('/')
        components = address.split('.')
        if (len(prefix) > 1 or not 1 <= len(components) <= 4
                or any(not re.fullmatch(r'[0-9]{1,3}', part) for part in components)):
            raise ValueError('Unrecognized host route')
        complete = '.'.join(components + ['0'] * (4 - len(components)))
        subnet = ipaddress.IPv4Network(complete + '/' + (prefix[0] if prefix else str(8 * len(components))), strict=False)
        if subnet.prefixlen:
            result.append(subnet)
        if len(result) > 8192:
            raise ValueError('Host route inventory too large')
    if not header or not seen:
        raise ValueError('Missing host route inventory')
    return result


def host_routes():
    system = platform.system()
    if system == 'Darwin':
        return darwin_routes(capture(['netstat', '-rn', '-f', 'inet']))
    if system == 'Linux':
        routes = json.loads(capture(['ip', '-j', '-4', 'route', 'show', 'table', 'all']))
        if not isinstance(routes, list) or not routes or len(routes) > 8192:
            raise ValueError('Invalid host route inventory')
        result = []
        for route in routes:
            if not isinstance(route, dict) or not isinstance(route.get('dst'), str):
                raise ValueError('Missing host route destination')
            if route['dst'] != 'default':
                subnet = ipaddress.IPv4Network(route['dst'], strict=False)
                if subnet.prefixlen:
                    result.append(subnet)
        return result
    raise ValueError('Unsupported host route inventory')


def validate_plan(plan):
    if not isinstance(plan, dict) or not 1 <= len(plan) <= MAX_NETWORKS:
        raise ValueError('Invalid bounded owned network plan')
    result = {}
    for name, config in plan.items():
        if not isinstance(name, str) or not re.fullmatch(r'[a-z][a-z0-9-]*', name):
            raise ValueError('Invalid logical network')
        if (not isinstance(config, dict) or set(config) != {'driver', 'config'} or config['driver'] != 'default'
                or not isinstance(config['config'], list) or len(config['config']) != 1):
            raise ValueError('Invalid closed owned IPAM')
        entry = config['config'][0]
        if (not isinstance(entry, dict) or set(entry) != {'subnet', 'gateway'}
                or not isinstance(entry['subnet'], str) or not isinstance(entry['gateway'], str)):
            raise ValueError('Invalid closed owned subnet')
        subnet = ipaddress.IPv4Network(entry['subnet'], strict=True)
        if (subnet.prefixlen != PREFIX or not subnet.subnet_of(POOL)
                or entry['gateway'] != str(subnet.network_address + 1)
                or any(subnet.overlaps(previous) for previous in result.values())):
            raise ValueError('Owned subnet outside reserved development profile')
        result[name] = subnet
    return result


def allocate(networks, project, occupied):
    if (not isinstance(networks, dict) or not 1 <= len(networks) <= MAX_NETWORKS
            or not re.fullmatch(r'dkp-bp04-[a-f0-9]{12}', project)):
        raise ValueError('Invalid owned network configuration')
    for name, config in networks.items():
        if (not isinstance(name, str) or not re.fullmatch(r'[a-z][a-z0-9-]*', name)
                or not isinstance(config, dict) or config.get('external', False) is not False
                or config.get('driver', 'bridge') != 'bridge' or config.get('ipam')
                or config.get('enable_ipv6', False) is not False
                or config.get('name', project + '_' + name) != project + '_' + name):
            raise ValueError('Cannot replace external or preconfigured network addressing')
    candidates = list(POOL.subnets(new_prefix=PREFIX))
    offset = int.from_bytes(hashlib.sha256(project.encode()).digest()[:4]) % len(candidates)
    chosen = {}
    for subnet in candidates[offset:] + candidates[:offset]:
        if any(subnet.overlaps(other) for other in occupied):
            continue
        chosen[sorted(networks)[len(chosen)]] = {'driver': 'default', 'config': [
            {'subnet': str(subnet), 'gateway': str(subnet.network_address + 1)}]}
        if len(chosen) == len(networks):
            validate_plan(chosen)
            return chosen
    raise ValueError('No nonconflicting bounded development addresses')


def assert_available(plan, occupied):
    if any(subnet.overlaps(other) for subnet in validate_plan(plan).values() for other in occupied):
        raise ValueError('Owned addresses now conflict; no network creation attempted')


def attest(plan, name, item):
    expected = validate_plan(plan)
    if name not in expected or item.get('Driver') != 'bridge' or item.get('EnableIPv6') is not False:
        raise ValueError('Owned network driver differs')
    ipam = item.get('IPAM')
    if not isinstance(ipam, dict) or ipam.get('Driver') != 'default' or ipam.get('Options') not in (None, {}):
        raise ValueError('Actual owned IPAM driver/options differ')
    config = ipam.get('Config')
    if (not isinstance(config, list) or len(config) != 1 or not isinstance(config[0], dict)
            or set(config[0]) != {'Subnet', 'Gateway'} or config[0]['Subnet'] != str(expected[name])
            or config[0]['Gateway'] != str(expected[name].network_address + 1)):
        raise ValueError('Actual owned network address differs')
