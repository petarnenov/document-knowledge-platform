#!/usr/bin/env python3
"""Verify the isolated foundation's HTTP surface and container restrictions."""
import json
import subprocess
import ssl
import urllib.error
import urllib.request
from pathlib import Path

COMPOSE = ["docker", "compose", "--env-file", "local-data/security/runtime.env", "-f", "infra/docker-compose/compose.yaml"]


def fetch(url, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    try:
        context = ssl.create_default_context(cafile=str(Path("local-data/security/ca.crt"))) if url.startswith("https:") else None
        with urllib.request.urlopen(request, timeout=10, context=context) as response:
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
    for path in ("/", "/foundation", "/identity", "/unknown-shell-route"):
        status, body = fetch("https://localhost:18443" + path)
        assert status == 200 and b'<div id="root">' in body, (path, status)
    status, _ = fetch("https://localhost:18443/api/v1/session")
    assert status == 401, ("anonymous session", status)
    for port in (18081, 18082, 18083):
        status, _ = fetch(f"http://127.0.0.1:{port}/api/v1/security/context", {"X-DKP-Tenant": "tenant-a"})
        assert status == 401, ("forged tenant without token", port, status)


def verify_containers():
    command = [*COMPOSE, "ps", "-q"]
    identifiers = subprocess.check_output(command, text=True).split()
    assert len(identifiers) == 6, identifiers
    containers = json.loads(subprocess.check_output(["docker", "inspect", *identifiers], text=True))
    for container in containers:
        name = container["Name"]
        assert container["Config"]["User"] not in ("", "0", "0:0", "root"), name
        assert container["HostConfig"]["ReadonlyRootfs"], name
        assert "ALL" in container["HostConfig"]["CapDrop"], name
        assert container["State"]["Health"]["Status"] == "healthy", name
        for bindings in container["HostConfig"]["PortBindings"].values():
            assert all(binding["HostIp"] == "127.0.0.1" for binding in bindings), name


def verify_identity_logs():
    marker = "DKP_SYNTHETIC_OAUTH_LOG_TEST_MARKER"
    fetch("https://localhost:18443/oauth/callback/keycloak?code=" + marker + "&state=" + marker)
    fetch("https://localhost:18443/oauth/callback/keycloak?error=access_denied&error_description=" + marker)
    output = subprocess.check_output([*COMPOSE, "logs", "--no-color", "--since", "30s", "admin-portal", "browser-bff"], text=True)
    # Never echo log contents: an assertion failure must not itself emit secrets.
    assert marker not in output, "Identity request material appeared in runtime logs"


if __name__ == "__main__":
    verify_http()
    verify_containers()
    verify_identity_logs()
    print("Identity smoke: probes, denial surface, trusted HTTPS portal and six non-root containers PASS")
