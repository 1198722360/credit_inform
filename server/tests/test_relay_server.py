from __future__ import annotations

import json
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from unittest import mock
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from relay_server import (  # noqa: E402
    KEEP_PLACEHOLDER,
    MonitorManager,
    RelayApplication,
    RelayState,
    create_http_server,
    parse_runtime_config,
    read_json_path,
)


GROUP_ID = "customer-service"
GROUP_TOKEN = "group-secret-token"
ADMIN_TOKEN = "admin-secret-token"


class JsonEndpointHandler(BaseHTTPRequestHandler):
    response_document = {"data": {"credit": 50}}

    def do_GET(self) -> None:  # noqa: N802
        payload = json.dumps(self.response_document).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format: str, *_args: object) -> None:
        pass


class RelayServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.config_file = self.root / "config.json"
        self.store_file = self.root / "monitor-configs.json"
        self.data_file = self.root / "relay-state.json"
        self.config_file.write_text(
            json.dumps(
                {
                    "groups": [{"id": GROUP_ID, "sharedToken": GROUP_TOKEN}],
                    "monitors": [],
                }
            ),
            encoding="utf-8",
        )
        self.state = RelayState(self.data_file)
        self.manager = MonitorManager(
            self.state,
            self.config_file,
            managed_store_file=self.store_file,
            reload_interval_seconds=0.5,
        )
        self.application = RelayApplication(
            self.state, self.manager, max_poll_seconds=3, admin_token=ADMIN_TOKEN
        )
        self.server = create_http_server("127.0.0.1", 0, self.application)
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join(timeout=3)
        self.manager.stop()
        self.temporary_directory.cleanup()

    def request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        token: str | None = None,
        timeout: float = 5,
    ) -> tuple[int, dict]:
        payload = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=payload,
            method=method,
            headers={
                "Accept": "application/json",
                **({"Content-Type": "application/json"} if payload is not None else {}),
                **({"Authorization": f"Bearer {token}"} if token is not None else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def register(self, client_id: str, client_name: str | None = None) -> dict:
        status, response = self.request(
            "POST",
            "/api/v1/clients/register",
            {
                "groupId": GROUP_ID,
                "clientId": client_id,
                "clientName": client_name or client_id,
            },
            GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        return response

    def test_manual_alert_long_poll_sender_exclusion_and_initial_cursor(self) -> None:
        status, health = self.request("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual("ok", health["status"])
        self.assertEqual(self.state.server_instance_id, health["serverInstanceId"])

        status, _ = self.request(
            "POST",
            "/api/v1/clients/register",
            {"groupId": GROUP_ID, "clientId": "bad", "clientName": "bad"},
            "wrong-token",
        )
        self.assertEqual(401, status)

        sender_registration = self.register("sender", "Customer service")
        receiver_registration = self.register("receiver", "Owner")
        self.assertEqual(0, sender_registration["cursor"])
        self.assertEqual(
            self.state.server_instance_id, sender_registration["serverInstanceId"]
        )
        self.assertEqual(2, receiver_registration["clients"]["online"])

        encoded_group = urllib.parse.quote(GROUP_ID)
        poll_path = (
            f"/api/v1/alerts?groupId={encoded_group}&clientId=receiver&after=0&wait=2"
        )
        with ThreadPoolExecutor(max_workers=1) as executor:
            pending_poll = executor.submit(
                self.request, "GET", poll_path, None, GROUP_TOKEN, 4
            )
            time.sleep(0.1)
            status, sent = self.request(
                "POST",
                "/api/v1/alerts",
                {
                    "groupId": GROUP_ID,
                    "senderId": "sender",
                    "senderName": "Customer service",
                    "message": "Wake up",
                },
                GROUP_TOKEN,
            )
            self.assertEqual(201, status)
            poll_status, polled = pending_poll.result(timeout=4)

        self.assertEqual(200, poll_status)
        self.assertEqual([sent["alert"]], polled["alerts"])
        self.assertEqual(self.state.server_instance_id, polled["serverInstanceId"])
        self.assertRegex(sent["alert"]["eventUid"], r"^[0-9a-f]{32}$")
        self.assertEqual(sent["alert"]["id"], polled["headCursor"])
        self.assertEqual("manual", polled["alerts"][0]["type"])
        event_id = sent["alert"]["id"]
        self.assertEqual(event_id, polled["cursor"])

        status, own_poll = self.request(
            "GET",
            f"/api/v1/alerts?groupId={encoded_group}&clientId=sender&after=0&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual([], own_poll["alerts"])
        self.assertEqual(event_id, own_poll["cursor"])

        late_registration = self.register("late-client")
        self.assertEqual(event_id, late_registration["cursor"])
        status, late_poll = self.request(
            "GET",
            f"/api/v1/alerts?groupId={encoded_group}&clientId=late-client"
            f"&after={event_id}&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual([], late_poll["alerts"])

    def test_event_sequence_survives_state_restart(self) -> None:
        first_instance_id = self.state.server_instance_id
        first, _, _ = self.state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="one",
            sender_name="One",
            message="first",
            excluded_client_id="one",
        )
        first_snapshot = self.data_file.read_text(encoding="utf-8")
        restarted = RelayState(self.data_file)
        self.assertEqual(first_instance_id, restarted.server_instance_id)
        self.assertEqual(
            first["eventUid"], restarted._events[GROUP_ID][0]["eventUid"]
        )
        second, _, _ = restarted.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="two",
            sender_name="Two",
            message="second",
            excluded_client_id="two",
        )
        self.assertGreater(second["id"], first["id"])

        self.data_file.write_text(first_snapshot, encoding="utf-8")
        rolled_back = RelayState(self.data_file)
        replacement, _, _ = rolled_back.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="replacement",
            sender_name="Replacement",
            message="replacement second",
            excluded_client_id="replacement",
        )
        self.assertEqual(second["id"], replacement["id"])
        self.assertNotEqual(second["eventUid"], replacement["eventUid"])

        rebuilt = RelayState(self.root / "rebuilt-state.json")
        self.assertNotEqual(first_instance_id, rebuilt.server_instance_id)

    def test_corrupt_state_fails_closed_without_overwriting_evidence(self) -> None:
        corrupt_file = self.root / "corrupt-state.json"
        corrupt_file.write_text("{not-json", encoding="utf-8")

        with self.assertRaises(RuntimeError):
            RelayState(corrupt_file)

        self.assertEqual("{not-json", corrupt_file.read_text(encoding="utf-8"))

    def test_poll_removes_events_that_expire_while_server_is_running(self) -> None:
        expiring_file = self.root / "expiring-state.json"
        state = RelayState(expiring_file, event_retention_seconds=1)
        event, _, _ = state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="sender",
            sender_name="Sender",
            message="old",
            excluded_client_id="sender",
        )
        state._events[GROUP_ID][0]["createdAt"] -= 2_000
        state._persist_locked()

        events, cursor = state.poll(GROUP_ID, "receiver", 0, 0)

        self.assertEqual([], events)
        self.assertEqual(0, cursor)
        self.assertEqual(event["id"], state.current_cursor(GROUP_ID))
        self.assertNotIn(event["id"], [item.get("id") for item in json.loads(
            expiring_file.read_text(encoding="utf-8")
        )["events"]])

    def test_append_rolls_back_memory_when_persistence_fails(self) -> None:
        state = RelayState(None)
        with mock.patch.object(state, "_persist_locked", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                state.append_event(
                    group_id=GROUP_ID,
                    event_type="manual",
                    sender_id="sender",
                    sender_name="Sender",
                    message="not durable",
                )

        self.assertEqual(0, state.current_cursor(GROUP_ID))
        self.assertEqual(1, state._next_event_id)

    def test_monitor_retries_alert_after_event_persistence_failure(self) -> None:
        monitor = self.manager.create_monitor_config(
            {
                "id": "retry-alert",
                "groupId": GROUP_ID,
                "name": "Retry alert",
                "url": "https://example.invalid/value",
                "jsonPath": "value",
                "operator": "<",
                "threshold": 10,
                "pollIntervalSeconds": 60,
                "timeoutSeconds": 5,
                "errorThreshold": 3,
                "repeatAlertSeconds": 0,
                "enabled": True,
            }
        )
        self.assertEqual("retry-alert", monitor["id"])

        with mock.patch.object(self.manager, "_fetch_value", return_value=(200, 1.0, 1.0)):
            with mock.patch.object(
                self.state, "append_event", side_effect=OSError("disk full")
            ):
                with self.assertRaises(OSError):
                    self.manager.check_monitor(GROUP_ID, "retry-alert")

            self.manager.check_monitor(GROUP_ID, "retry-alert")

        events, _ = self.state.poll(GROUP_ID, "receiver", 0, 0)
        self.assertEqual(["monitor_breach"], [event["type"] for event in events])

    def test_admin_crud_masks_secrets_and_mask_update_retains_them(self) -> None:
        unsafe_application = RelayApplication(
            self.state, self.manager, admin_token=GROUP_TOKEN
        )
        self.assertFalse(
            unsafe_application.authorize_admin(GROUP_TOKEN),
            "a token that is also a group token must never gain admin access",
        )
        create_body = {
            "groupId": GROUP_ID,
            "name": "Private balance",
            "url": "https://example.invalid/balance?apiKey=url-secret&view=full",
            "method": "POST",
            "headersJson": json.dumps(
                {"Authorization": "Bearer header-secret", "X-Api-Key": "header-key"}
            ),
            "body": {"account": "main", "token": "body-secret"},
            "jsonPath": "data.credit",
            "operator": "<",
            "threshold": 10,
            "multiplier": 0.1,
            "offset": 0,
            "pollIntervalSeconds": 60,
            "timeoutSeconds": 5,
            "errorThreshold": 2,
            "repeatAlertSeconds": 300,
            "enabled": False,
        }
        status, _ = self.request(
            "POST", "/api/v1/admin/monitors", create_body, GROUP_TOKEN
        )
        self.assertEqual(401, status, "a normal group token must not mutate monitors")
        status, _ = self.request(
            "GET",
            f"/api/v1/admin/monitors?groupId={GROUP_ID}",
            token=GROUP_TOKEN,
        )
        self.assertEqual(401, status, "a normal group token must not read monitor secrets")

        status, created = self.request(
            "POST", "/api/v1/admin/monitors", create_body, ADMIN_TOKEN
        )
        self.assertEqual(201, status)
        monitor = created["monitor"]
        monitor_id = monitor["id"]
        self.assertEqual(KEEP_PLACEHOLDER, monitor["body"])
        self.assertIn(KEEP_PLACEHOLDER, monitor["headersJson"])
        self.assertIn(KEEP_PLACEHOLDER, monitor["url"])
        serialized = json.dumps(created)
        for secret in ("url-secret", "full", "header-secret", "header-key", "body-secret"):
            self.assertNotIn(secret, serialized)

        status, listed = self.request(
            "GET",
            f"/api/v1/admin/monitors?groupId={GROUP_ID}",
            token=ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        serialized = json.dumps(listed)
        for secret in ("url-secret", "full", "header-secret", "header-key", "body-secret"):
            self.assertNotIn(secret, serialized)

        update_body = {
            "groupId": GROUP_ID,
            "name": "Renamed balance",
            "url": monitor["url"],
            "headersJson": monitor["headersJson"],
            "body": KEEP_PLACEHOLDER,
            "revision": monitor["revision"],
        }
        status, updated = self.request(
            "PUT",
            f"/api/v1/admin/monitors/{monitor_id}",
            update_body,
            ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual("Renamed balance", updated["monitor"]["name"])
        self.assertEqual(monitor["revision"] + 1, updated["monitor"]["revision"])
        stored_text = self.store_file.read_text(encoding="utf-8")
        for secret in ("url-secret", "full", "header-secret", "header-key", "body-secret"):
            self.assertIn(secret, stored_text)

        status, cleared = self.request(
            "PUT",
            f"/api/v1/admin/monitors/{monitor_id}",
            {
                "groupId": GROUP_ID,
                "body": None,
                "revision": updated["monitor"]["revision"],
            },
            ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertIsNone(cleared["monitor"]["body"])
        self.assertNotIn("body-secret", self.store_file.read_text(encoding="utf-8"))

        status, conflict = self.request(
            "PUT",
            f"/api/v1/admin/monitors/{monitor_id}",
            {"groupId": GROUP_ID, "name": "Stale", "revision": monitor["revision"]},
            ADMIN_TOKEN,
        )
        self.assertEqual(409, status)
        self.assertEqual("revision_conflict", conflict["error"]["code"])

        status, _ = self.request(
            "DELETE",
            f"/api/v1/admin/monitors/{monitor_id}?groupId={GROUP_ID}",
            token=GROUP_TOKEN,
        )
        self.assertEqual(401, status)
        status, deleted = self.request(
            "DELETE",
            f"/api/v1/admin/monitors/{monitor_id}?groupId={GROUP_ID}",
            token=ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertTrue(deleted["ok"])

    def test_monitor_test_status_and_automatic_breach_event(self) -> None:
        endpoint = ThreadingHTTPServer(("127.0.0.1", 0), JsonEndpointHandler)
        endpoint_thread = threading.Thread(target=endpoint.serve_forever, daemon=True)
        endpoint_thread.start()
        try:
            status, created = self.request(
                "POST",
                "/api/v1/admin/monitors",
                {
                    "id": "main-balance",
                    "groupId": GROUP_ID,
                    "name": "Main balance",
                    "url": f"http://127.0.0.1:{endpoint.server_port}/balance",
                    "method": "GET",
                    "headersJson": "{}",
                    "body": None,
                    "jsonPath": "data.credit",
                    "operator": "<",
                    "threshold": 10,
                    "multiplier": 0.1,
                    "offset": 0,
                    "pollIntervalSeconds": 60,
                    "timeoutSeconds": 5,
                    "errorThreshold": 2,
                    "repeatAlertSeconds": 300,
                    "enabled": False,
                },
                ADMIN_TOKEN,
            )
            self.assertEqual(201, status)
            self.assertEqual("main-balance", created["monitor"]["id"])

            status, test_result = self.request(
                "POST",
                "/api/v1/admin/monitors/main-balance/test",
                {"groupId": GROUP_ID},
                ADMIN_TOKEN,
            )
            self.assertEqual(200, status)
            self.assertTrue(test_result["ok"])
            self.assertEqual(50, test_result["result"]["rawValue"])
            self.assertEqual(5, test_result["result"]["value"])
            self.assertTrue(test_result["result"]["breached"])

            registration = self.register("owner")
            self.manager.check_monitor(GROUP_ID, "main-balance")
            status, monitor_status = self.request(
                "GET",
                f"/api/v1/monitors?groupId={GROUP_ID}",
                token=GROUP_TOKEN,
            )
            self.assertEqual(200, status)
            self.assertTrue(monitor_status["monitors"][0]["breached"])
            self.assertEqual(5, monitor_status["monitors"][0]["lastValue"])

            status, alerts = self.request(
                "GET",
                f"/api/v1/alerts?groupId={GROUP_ID}&clientId=owner"
                f"&after={registration['cursor']}&wait=0",
                token=GROUP_TOKEN,
            )
            self.assertEqual(200, status)
            self.assertEqual(1, len(alerts["alerts"]))
            self.assertEqual("monitor_breach", alerts["alerts"][0]["type"])
            self.assertEqual("main-balance", alerts["alerts"][0]["monitorId"])
        finally:
            endpoint.shutdown()
            endpoint.server_close()
            endpoint_thread.join(timeout=3)

    def test_monitor_request_errors_do_not_expose_configured_secrets(self) -> None:
        status, created = self.request(
            "POST",
            "/api/v1/admin/monitors",
            {
                "id": "invalid-secret-header",
                "groupId": GROUP_ID,
                "name": "Invalid secret header",
                "url": "https://example.invalid/balance?token=url-secret",
                "method": "GET",
                "headersJson": json.dumps(
                    {"Authorization": "Bearer header-secret\nInjected: true"}
                ),
                "jsonPath": "data.credit",
                "operator": "<",
                "threshold": 10,
                "pollIntervalSeconds": 60,
                "timeoutSeconds": 1,
                "errorThreshold": 1,
                "repeatAlertSeconds": 300,
                "enabled": False,
            },
            ADMIN_TOKEN,
        )
        self.assertEqual(201, status)
        self.assertEqual("invalid-secret-header", created["monitor"]["id"])

        status, tested = self.request(
            "POST",
            "/api/v1/admin/monitors/invalid-secret-header/test",
            {"groupId": GROUP_ID},
            ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertFalse(tested["ok"])

        registration = self.register("secret-check-owner")
        self.manager.check_monitor(GROUP_ID, "invalid-secret-header")
        status, monitor_status = self.request(
            "GET",
            f"/api/v1/monitors?groupId={GROUP_ID}",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        status, alerts = self.request(
            "GET",
            f"/api/v1/alerts?groupId={GROUP_ID}&clientId=secret-check-owner"
            f"&after={registration['cursor']}&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)

        public_payload = json.dumps(
            {"tested": tested, "monitors": monitor_status, "alerts": alerts}
        )
        self.assertNotIn("url-secret", public_payload)
        self.assertNotIn("header-secret", public_payload)
        self.assertIn("request failed (ValueError)", public_payload)

    def test_json_path_supports_index_and_filter(self) -> None:
        document = {
            "accounts": [{"credit": 2}],
            "balance_infos": [
                {"currency": "USD", "total_balance": 3},
                {"currency": "CNY", "total_balance": 9},
            ],
        }
        self.assertEqual(2, read_json_path(document, "accounts[0].credit"))
        self.assertEqual(
            9,
            read_json_path(
                document, "balance_infos[currency=CNY].total_balance"
            ),
        )

    def test_monitor_configuration_enforces_safe_ranges(self) -> None:
        monitor = {
            "id": "range-test",
            "groupId": GROUP_ID,
            "name": "Range test",
            "url": "https://example.invalid/value",
            "method": "GET",
            "headers": {},
            "jsonPath": "value",
            "operator": "<",
            "threshold": 1,
            "pollIntervalSeconds": 5,
            "timeoutSeconds": 60,
            "errorThreshold": 100,
            "repeatAlertSeconds": 15,
        }
        groups = [{"id": GROUP_ID, "sharedToken": GROUP_TOKEN}]
        parse_runtime_config({"groups": groups, "monitors": [monitor]})
        parse_runtime_config(
            {"groups": groups, "monitors": [{**monitor, "repeatAlertSeconds": 0}]}
        )

        invalid_values = (
            ("pollIntervalSeconds", 4),
            ("timeoutSeconds", 61),
            ("errorThreshold", 0),
            ("errorThreshold", 101),
            ("repeatAlertSeconds", 14),
            ("repeatAlertSeconds", -1),
        )
        for field_name, value in invalid_values:
            with self.subTest(field_name=field_name, value=value):
                with self.assertRaises(ValueError):
                    parse_runtime_config(
                        {
                            "groups": groups,
                            "monitors": [{**monitor, field_name: value}],
                        }
                    )

    def test_managed_store_is_imported_once_and_then_authoritative(self) -> None:
        nested = self.root / "managed-import"
        nested.mkdir()
        config_file = nested / "config.json"
        store_file = nested / "monitor-configs.json"

        def monitor_document(monitor_id: str) -> dict:
            return {
                "id": monitor_id,
                "groupId": GROUP_ID,
                "name": monitor_id,
                "url": "https://example.invalid/value",
                "method": "GET",
                "headers": {},
                "jsonPath": "value",
                "operator": "<",
                "threshold": 1,
                "pollIntervalSeconds": 60,
                "timeoutSeconds": 5,
                "errorThreshold": 3,
                "repeatAlertSeconds": 300,
                "enabled": False,
            }

        def write_base(monitor_id: str) -> None:
            config_file.write_text(
                json.dumps(
                    {
                        "groups": [{"id": GROUP_ID, "sharedToken": GROUP_TOKEN}],
                        "monitors": [monitor_document(monitor_id)],
                    }
                ),
                encoding="utf-8",
            )

        write_base("initial")
        manager = MonitorManager(
            RelayState(None), config_file, managed_store_file=store_file
        )
        try:
            self.assertEqual("initial", manager.list_monitor_configs(GROUP_ID)[0]["id"])
            self.assertTrue(store_file.exists())

            write_base("base-change-is-not-reimported")
            manager.reload(force=True)
            self.assertEqual("initial", manager.list_monitor_configs(GROUP_ID)[0]["id"])

            external = monitor_document("managed-change")
            external["revision"] = 1
            external["updatedAt"] = 1
            store_file.write_text(
                json.dumps({"monitors": [external]}), encoding="utf-8"
            )
            manager.reload(force=True)
            self.assertEqual(
                "managed-change", manager.list_monitor_configs(GROUP_ID)[0]["id"]
            )
        finally:
            manager.stop()

    def test_global_token_can_run_without_a_base_config_file(self) -> None:
        nested = self.root / "global-token-only"
        manager = MonitorManager(
            RelayState(None),
            nested / "missing-config.json",
            managed_store_file=nested / "monitor-configs.json",
            global_token="global-shared-token",
        )
        try:
            self.assertTrue(manager.config_status()["loaded"])
            self.assertTrue(manager.authorize("any-group", "global-shared-token"))
            self.assertEqual([], manager.list_monitor_configs("any-group"))
        finally:
            manager.stop()


if __name__ == "__main__":
    unittest.main()
