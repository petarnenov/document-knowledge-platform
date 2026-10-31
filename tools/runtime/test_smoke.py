"""Ensure smoke checks reject leaking probes and unintended exposed routes."""
import unittest
from unittest.mock import patch

import smoke


def healthy(url, headers=None):
    if "/health/" in url:
        return 200, b'{"status":"UP"}'
    if ":18080" in url:
        return 200, b'<div id="root"></div>'
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


if __name__ == "__main__":
    unittest.main()
