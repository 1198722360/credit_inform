from __future__ import annotations

import base64
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from decimal import Decimal
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import gateway_client as gateway
from relay_server import MonitorManager, RelayState, compare_value, now_ms, parse_runtime_config

# Published interoperability fixtures, containing synthetic credentials only.
# Source: https://www.privapi.cc/open-api/test-vectors.json
VECTOR = json.loads((Path(__file__).parent / "fixtures/gateway-test-vectors.json").read_text(encoding="utf-8"))
AK, SK = VECTOR["access_key"], VECTOR["secret_key"]
URL = "https://op.privapi.cc/open/v1/balance/query"


class Reply(io.BytesIO):
    def __init__(self, document, code=200, headers=None):
        super().__init__(json.dumps(document).encode() if not isinstance(document, bytes) else document)
        self.code = code
        self.headers = headers or {}


def responding(data=None, *, code="OK", status=200, headers=None, wrong_nonce=False, requests=None):
    """An independent peer decrypts the request before encrypting its response."""
    req_key, resp_key = gateway.derive_keys(AK, SK)

    def respond(request, timeout):
        nonce = request.get_header("X-gw-nonce")
        timestamp = request.get_header("X-gw-timestamp")
        assert request.method == "POST"
        assert request.get_header("X-gw-access-key") == AK
        assert abs(int(timestamp) - now_ms() // 1000) <= 1
        body = json.loads(request.data)
        plaintext = gateway.open_payload(req_key, body["payload"], gateway.request_aad(request.selector, AK, timestamp, nonce))
        params = json.loads(plaintext)
        if requests is not None:
            requests.append((nonce, base64.b64decode(body["payload"])[:12], params))
        payload = gateway.seal(resp_key, json.dumps({"code": code, "message": SK, "data": data or {"balance": "5.123456"}}).encode(), gateway.response_aad(AK, "different-nonce" if wrong_nonce else nonce))
        return Reply({"request_id": "test-request-123", "payload": payload}, status, headers)

    return respond


class GatewayProtocolTest(unittest.TestCase):
    def test_official_key_derivation_and_request_vector(self):
        req, resp = gateway.derive_keys(AK, SK)
        self.assertEqual(VECTOR["derive"]["req_key"]["hex"], req.hex())
        self.assertEqual(VECTOR["derive"]["resp_key"]["hex"], resp.hex())
        request = VECTOR["request"]
        aad = gateway.request_aad(request["path"], AK, request["timestamp"], request["nonce"])
        self.assertEqual(request["aad"].encode(), aad)
        self.assertEqual(request["payload"], gateway.seal(req, b"{}", aad, iv=bytes.fromhex(request["iv_hex"])))

    def test_official_response_vector(self):
        _, resp = gateway.derive_keys(AK, SK)
        aad = gateway.response_aad(AK, VECTOR["request"]["nonce"])
        self.assertEqual(VECTOR["response"]["aad"].encode(), aad)
        self.assertEqual(VECTOR["response"]["plaintext"], gateway.open_payload(resp, VECTOR["response"]["payload"], aad).decode())

    def test_official_negative_vectors_reject_wrong_key_path_timestamp_and_direction(self):
        req, resp = gateway.derive_keys(AK, SK)
        request = VECTOR["request"]
        wrong_sk = "gwsk_" + base64.urlsafe_b64encode(bytes(range(0x11, 0x31))).decode().rstrip("=")
        wrong_req, _ = gateway.derive_keys(AK, wrong_sk)
        cases = [
            (wrong_req, request["payload"], request["aad"]),
            (req, request["payload"], request["aad"].replace("1792646400", "1792646401")),
            (req, request["payload"], request["aad"].replace("balance", "nodes")),
            (resp, request["payload"], VECTOR["response"]["aad"]),
            (resp, VECTOR["response"]["payload"], VECTOR["hook"]["aad"]),
        ]
        for key, payload, aad in cases:
            with self.subTest(aad=aad), self.assertRaises(Exception):
                gateway.open_payload(key, payload, aad.encode())

    def test_each_request_has_new_nonce_and_iv_and_encrypts_business_params(self):
        requests = []
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=responding(requests=requests)):
            for _ in range(2):
                status, result = gateway.call(URL, AK, SK, {"page": 1}, 10)
                self.assertEqual(200, status)
                self.assertEqual("5.123456", result["data"]["balance"])
        self.assertNotEqual(requests[0][0], requests[1][0])
        self.assertNotEqual(requests[0][1], requests[1][1])
        self.assertEqual({"page": 1}, requests[0][2])

    def test_rejects_response_replayed_from_another_request(self):
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=responding(wrong_nonce=True)):
            with self.assertRaisesRegex(gateway.GatewayError, "failed authentication"):
                gateway.call(URL, AK, SK, None, 10)

    def test_plain_auth_error_is_sanitized_and_preserves_request_id(self):
        response = Reply({"code": "AUTH_FAILED", "message": SK, "request_id": "trace-42"}, 401)
        error = urllib.error.HTTPError(URL, 401, "Unauthorized", {}, response)
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=error):
            with self.assertRaises(gateway.GatewayError) as raised:
                gateway.call(URL, AK, SK, None, 10)
        self.assertEqual(401, raised.exception.http_code)
        self.assertIn("AUTH_FAILED", str(raised.exception))
        self.assertIn("trace-42", str(raised.exception))
        self.assertNotIn(SK, str(raised.exception))

    def test_encrypted_business_error_and_rate_limit(self):
        for code, status, headers in [("SCOPE_DENIED", 403, {}), ("RATE_LIMITED", 429, {"Retry-After": "120"})]:
            with self.subTest(code=code), mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=responding(code=code, status=status, headers=headers)):
                with self.assertRaises(gateway.GatewayError) as raised:
                    gateway.call(URL, AK, SK, None, 10)
                self.assertEqual(status, raised.exception.http_code)
                self.assertIn(code, str(raised.exception))
                self.assertNotIn(SK, str(raised.exception))
                self.assertEqual(int(headers.get("Retry-After", "0")), raised.exception.retry_after)

    def test_plaintext_success_and_malformed_payload_are_rejected(self):
        for body in [{"code": "OK", "data": {"balance": "999"}}, {"payload": "%%%"}, [], b"not-json", {"payload": ""}]:
            with self.subTest(body=body), mock.patch("gateway_client.urllib.request.OpenerDirector.open", return_value=Reply(body)):
                with self.assertRaises(gateway.GatewayError):
                    gateway.call(URL, AK, SK, {}, 10)

    def test_request_limits_and_exact_url_validation(self):
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open") as network:
            with self.assertRaisesRegex(gateway.GatewayError, "32 KiB"):
                gateway.call(URL, AK, SK, {"text": "x" * 25000}, 10)
            network.assert_not_called()
        for url in [URL + "/", URL + "?", URL + "?a=1", URL + "#a", URL.replace("https:", "http:"), URL.replace("op.privapi.cc", "user:pass@op.privapi.cc")]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                gateway.validate_url(url)
        self.assertIsNone(gateway._NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example/"))

    def test_response_size_limit(self):
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open", return_value=Reply(b"x" * (gateway.MAX_RESPONSE_BYTES + 1))):
            with self.assertRaisesRegex(gateway.GatewayError, "1 MiB"):
                gateway.call(URL, AK, SK, {}, 10)


class GatewayMonitorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = self.root / "config.json"
        self.store = self.root / "monitors.json"
        self.config.write_text(json.dumps({"groups": [{"id": "team", "sharedToken": "relay-token"}], "monitors": []}), encoding="utf-8")
        self.state = RelayState(self.root / "events.json")
        self.manager = MonitorManager(self.state, self.config, managed_store_file=self.store)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.manager.stop)
        self.monitor = {
            "id": "gateway-balance", "groupId": "team", "name": "GateWay balance",
            "provider": "gateway", "url": URL, "method": "POST", "headers": {}, "body": {},
            "gatewayAccessKey": AK, "gatewaySecretKeyEnv": "GATEWAY_TEST_SK",
            "jsonPath": "data.balance", "threshold": 10, "pollIntervalSeconds": 60,
        }
        env = mock.patch.dict(os.environ, {"GATEWAY_TEST_SK": SK})
        env.start()
        self.addCleanup(env.stop)

    def test_crud_reload_copy_preserve_provider_but_never_persist_sk(self):
        created = self.manager.create_monitor_config(self.monitor)
        self.assertEqual("gateway", created["provider"])
        updated = self.manager.update_monitor_config("team", created["id"], {"name": "Renamed", "revision": created["revision"]})
        self.assertEqual(AK, updated["gatewayAccessKey"])
        copied = self.manager.copy_monitor_config("team", created["id"])
        self.assertEqual("gateway", copied["provider"])
        self.assertEqual("GATEWAY_TEST_SK", copied["gatewaySecretKeyEnv"])
        self.assertFalse(copied["enabled"])
        self.assertNotIn(SK, self.store.read_text(encoding="utf-8"))
        self.manager.reload(force=True)
        self.assertEqual("gateway", self.manager.list_monitor_configs("team")[0]["provider"])

    def test_encrypted_balance_test_refresh_and_automatic_alert(self):
        self.manager.create_monitor_config(self.monitor)
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=responding()):
            result = self.manager.test_monitor_config("team", "gateway-balance")
            self.assertTrue(result["ok"])
            self.assertTrue(result["result"]["breached"])
            self.assertEqual(5.123456, result["result"]["value"])
            json.dumps(result)  # Public API remains JSON serializable.
            self.assertEqual(0, self.state.current_cursor("team"))
            self.assertTrue(self.manager.refresh_monitor_configs("team")["ok"])
            self.manager.check_monitor("team", "gateway-balance")
        self.assertEqual(1, self.state.current_cursor("team"))
        public = self.manager.statuses_for_group("team")
        self.assertTrue(public[0]["breached"])
        self.assertNotIn(SK, json.dumps(public))

    def test_decimal_conversion_and_comparison_are_exact(self):
        self.manager.create_monitor_config({**self.monitor, "multiplier": 3, "threshold": 0.3, "operator": "="})
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=responding({"balance": "0.1"})):
            result = self.manager.test_monitor_config("team", "gateway-balance")
        self.assertTrue(result["result"]["breached"])
        self.assertTrue(compare_value(Decimal("0.3"), "=", 0.3))
        self.assertFalse(compare_value(Decimal("0.3000001"), "=", 0.3))
        self.assertFalse(compare_value(Decimal("0.3"), "<", 0.3))

    def test_missing_or_invalid_sk_does_not_leak_credentials(self):
        self.manager.create_monitor_config(self.monitor)
        for secret in ["", "deliberately-invalid-private-secret"]:
            with mock.patch.dict(os.environ, {"GATEWAY_TEST_SK": secret}):
                result = self.manager.test_monitor_config("team", "gateway-balance")
            self.assertFalse(result["ok"])
            if secret:
                self.assertNotIn(secret, json.dumps(result))

    def test_rate_limit_delays_next_automatic_poll_and_triggers_error_alert(self):
        self.manager.create_monitor_config({**self.monitor, "errorThreshold": 1})
        with mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=responding(code="RATE_LIMITED", status=429, headers={"Retry-After": "120"})):
            before = now_ms()
            self.manager.check_monitor("team", "gateway-balance")
        status = self.manager.statuses_for_group("team")[0]
        self.assertGreaterEqual(status["nextCheckAt"], before + 120000)
        self.assertEqual(429, status["lastHttpCode"])
        self.assertEqual(1, self.state.current_cursor("team"))
        self.assertNotIn(SK, status["lastError"])

    def test_invalid_money_and_business_error_never_become_balance(self):
        self.manager.create_monitor_config(self.monitor)
        for data in [{"balance": "NaN"}, {"balance": None}, {"balance": True}, {"balance": "Infinity"}, {"balance": "1e9999"}, {"balance": "not-a-number"}]:
            with self.subTest(data=data), mock.patch("gateway_client.urllib.request.OpenerDirector.open", side_effect=responding(data)):
                self.assertFalse(self.manager.test_monitor_config("team", "gateway-balance")["ok"])

    def test_gateway_config_rejects_static_auth_query_bad_ak_and_short_poll(self):
        changes = [{"gatewayAccessKey": "bad"}, {"gatewaySecretKeyEnv": SK}, {"headers": {"Authorization": "static"}}, {"method": "GET"}, {"pollIntervalSeconds": 5}, {"body": []}, {"url": URL + "/"}, {"url": URL + "?a=b"}, {"provider": "unknown"}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                parse_runtime_config({"monitors": [{**self.monitor, **change}]})


if __name__ == "__main__":
    unittest.main()
