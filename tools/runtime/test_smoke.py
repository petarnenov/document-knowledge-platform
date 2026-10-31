"""Ensure smoke checks reject leaking probes and unintended exposed routes."""
import unittest
import json
from copy import deepcopy
from unittest.mock import patch

import smoke


def healthy(url, headers=None):
    if "/health/" in url:
        return 200, b'{"status":"UP"}'
    if ":18443" in url and "/api/" not in url:
        return 200, b'<div id="root"></div>'
    if "/api/v1/session" in url or "/api/v1/security/context" in url:
        return 401, b'{"code":"UNAUTHENTICATED"}'
    return 404, b""


class SmokeTests(unittest.TestCase):
    @patch("smoke.fetch", side_effect=healthy)
    def test_accepts_expected_surface(self, _fetch):
        smoke.verify_http()

    def test_rejects_sensitive_probe_details(self):
        with patch("smoke.fetch", return_value=(200, b'{"status":"UP","details":"private"}')):
            with self.assertRaises(AssertionError):
                smoke.verify_http()

    def test_rejects_accidental_business_endpoint(self):
        def exposed(url, headers=None):
            if "/api/" in url:
                return 200, b"{}"
            return healthy(url, headers)
        with patch("smoke.fetch", side_effect=exposed):
            with self.assertRaises(AssertionError):
                smoke.verify_http()

    def test_container_restrictions_and_negative_runtime_fixtures(self):
        safe = {"Name": "synthetic-container", "Config": {"User": "1001:1001"},
                "HostConfig": {"ReadonlyRootfs": True, "CapDrop": ["ALL"],
                               "PortBindings": {"8080/tcp": [{"HostIp": "127.0.0.1"}]}},
                "State": {"Health": {"Status": "healthy"}}}

        def verify(containers):
            with patch("smoke.subprocess.check_output", side_effect=["a b c d e f g", json.dumps(containers)]):
                smoke.verify_containers()

        verify([deepcopy(safe) for _ in range(7)])
        for section, key, value in [("Config", "User", "root"),
                                    ("HostConfig", "ReadonlyRootfs", False),
                                    ("HostConfig", "CapDrop", []),
                                    ("HostConfig", "PortBindings", {"8080/tcp": [{"HostIp": "0.0.0.0"}]})]:
            with self.subTest(section=section, key=key):
                unsafe = deepcopy(safe)
                unsafe[section][key] = value
                with self.assertRaises(AssertionError):
                    verify([unsafe])
        unhealthy = deepcopy(safe)
        unhealthy["State"]["Health"]["Status"] = "unhealthy"
        with self.assertRaises(AssertionError):
            verify([unhealthy])
        with patch("smoke.subprocess.check_output", return_value="a b c"):
            with self.assertRaises(AssertionError):
                smoke.verify_containers()

    def test_callback_log_markers_are_rejected_without_echoing_logs(self):
        with patch("smoke.fetch", return_value=(401, b"{}")), \
             patch("smoke.subprocess.check_output", return_value="safe operational output"):
            smoke.verify_identity_logs()
        with patch("smoke.fetch", return_value=(401, b"{}")), \
             patch("smoke.subprocess.check_output", return_value="private DKP_SYNTHETIC_OAUTH_LOG_TEST_MARKER"):
            with self.assertRaisesRegex(AssertionError, "^Identity request material appeared in runtime logs$"):
                smoke.verify_identity_logs()


if __name__ == "__main__":
    unittest.main()
