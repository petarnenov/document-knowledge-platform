#!/usr/bin/env python3
"""Verify the isolated foundation's HTTP surface and container restrictions."""
import json
import subprocess
import urllib.error
import urllib.request


def fetch(url, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def verify_http():
    for port in (18081, 18082, 18083):
        base = f"http://127.0.0.1:{port}"
        for probe in ("liveness", "readiness"):
            status, body = fetch(f"{base}/health/{probe}")
            assert status == 200 and json.loads(body) == {"status": "UP"}, (port, probe, status, body)
        for path in ("/api/v1/documents", "/api/v1/knowledge/collections", "/api/v1/search", "/env", "/beans", "/routes"):
            status, _ = fetch(base + path, {"X-Tenant-Id": "synthetic-tenant"})
            assert status >= 400, (port, path, status)
    for path in ("/", "/foundation", "/unknown-shell-route"):
        status, body = fetch("http://127.0.0.1:18080" + path)
        assert status == 200 and b'<div id="root">' in body, (path, status)


def verify_containers():
    command = ["docker", "compose", "-f", "infra/docker-compose/compose.yaml", "ps", "-q"]
    identifiers = subprocess.check_output(command, text=True).split()
    assert len(identifiers) == 4, identifiers
    containers = json.loads(subprocess.check_output(["docker", "inspect", *identifiers], text=True))
    for container in containers:
        name = container["Name"]
        assert container["Config"]["User"] not in ("", "0", "0:0", "root"), name
        assert container["HostConfig"]["ReadonlyRootfs"], name
        assert "ALL" in container["HostConfig"]["CapDrop"], name
        assert container["State"]["Health"]["Status"] == "healthy", name
        for bindings in container["HostConfig"]["PortBindings"].values():
            assert all(binding["HostIp"] == "127.0.0.1" for binding in bindings), name


if __name__ == "__main__":
    verify_http()
    verify_containers()
    print("Foundation smoke: probes, restricted surface, portal fallback and four non-root containers PASS")
