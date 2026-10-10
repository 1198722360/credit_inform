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
    MonitorFetchError,
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

    @staticmethod
    def monitor_request(
        monitor_id: str,
        name: str,
        group_id: str = GROUP_ID,
        **overrides: object,
    ) -> dict:
        document = {
            "id": monitor_id,
            "groupId": group_id,
            "name": name,
            "url": "https://example.invalid/value",
            "method": "GET",
            "headers": {},
            "body": None,
            "jsonPath": "data.credit",
            "operator": "<",
            "threshold": 10,
            "multiplier": 1,
            "offset": 0,
            "pollIntervalSeconds": 60,
            "timeoutSeconds": 5,
            "errorThreshold": 2,
            "repeatAlertSeconds": 300,
            "enabled": False,
        }
        document.update(overrides)
        return document

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
        self.assertIsNone(sender_registration["cursorToken"])
        self.assertEqual(0, sender_registration["headCursor"])
        self.assertIsNone(sender_registration["headEventUid"])
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
        self.assertEqual(sent["alert"]["eventUid"], polled["headEventUid"])
        self.assertEqual(sent["alert"]["eventUid"], polled["cursorToken"])
        self.assertTrue(polled["cursorValid"])
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
        self.assertEqual(sent["alert"]["eventUid"], own_poll["cursorToken"])
        self.assertTrue(own_poll["cursorValid"])

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

        status, heartbeat = self.request(
            "POST",
            "/api/v1/clients/heartbeat",
            {
                "groupId": GROUP_ID,
                "clientId": "receiver",
                "clientName": "Owner",
            },
            GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual(event_id, heartbeat["cursor"])
        self.assertEqual(sent["alert"]["eventUid"], heartbeat["cursorToken"])
        self.assertEqual(event_id, heartbeat["headCursor"])
        self.assertEqual(sent["alert"]["eventUid"], heartbeat["headEventUid"])

        status, relay_status = self.request(
            "GET", f"/api/v1/status?groupId={encoded_group}", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual(event_id, relay_status["cursor"])
        self.assertEqual(sent["alert"]["eventUid"], relay_status["cursorToken"])
        self.assertEqual(event_id, relay_status["headCursor"])
        self.assertEqual(sent["alert"]["eventUid"], relay_status["headEventUid"])

    def test_single_group_api_can_omit_group_and_client_names(self) -> None:
        status, receiver = self.request(
            "POST",
            "/api/v1/clients/register",
            {"clientId": "receiver"},
            GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual(GROUP_ID, receiver["client"]["groupId"])
        self.assertEqual("receiver", receiver["client"]["name"])

        status, sender = self.request(
            "POST",
            "/api/v1/clients/heartbeat",
            {"clientId": "sender"},
            GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual("sender", sender["client"]["name"])

        status, sent = self.request(
            "POST",
            "/api/v1/alerts",
            {"senderId": "sender", "message": "single tenant alert"},
            GROUP_TOKEN,
        )
        self.assertEqual(201, status)
        self.assertEqual(GROUP_ID, sent["alert"]["groupId"])
        self.assertEqual("sender", sent["alert"]["senderName"])

        status, alerts = self.request(
            "GET",
            "/api/v1/alerts?clientId=receiver&after=0&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual([sent["alert"]], alerts["alerts"])

        status, relay_status = self.request(
            "GET", "/api/v1/status", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual(GROUP_ID, relay_status["groupId"])
        self.assertEqual(2, relay_status["clients"]["total"])

        monitor_document = self.monitor_request("single-tenant", "Single tenant")
        del monitor_document["groupId"]
        status, created = self.request(
            "POST", "/api/v1/admin/monitors", monitor_document, ADMIN_TOKEN
        )
        self.assertEqual(201, status)
        monitor = created["monitor"]
        self.assertEqual(GROUP_ID, monitor["groupId"])

        status, listed = self.request(
            "GET", "/api/v1/admin/monitors", token=ADMIN_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual([monitor["id"]], [item["id"] for item in listed["monitors"]])

        status, updated = self.request(
            "PUT",
            f"/api/v1/admin/monitors/{monitor['id']}",
            {"name": "Updated", "revision": monitor["revision"]},
            ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual("Updated", updated["monitor"]["name"])

        with mock.patch.object(
            self.manager, "_fetch_value", return_value=(200, 25, 25.0)
        ):
            status, tested = self.request(
                "POST",
                f"/api/v1/admin/monitors/{monitor['id']}/test",
                {},
                ADMIN_TOKEN,
            )
        self.assertEqual(200, status)
        self.assertTrue(tested["ok"])
        self.assertEqual(25.0, tested["result"]["value"])

        status, tested_status = self.request(
            "GET", "/api/v1/monitors", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual(25.0, tested_status["monitors"][0]["lastValue"])
        self.assertEqual(200, tested_status["monitors"][0]["lastHttpCode"])
        self.assertGreater(tested_status["monitors"][0]["lastCheckAt"], 0)

        status, copied = self.request(
            "POST",
            f"/api/v1/admin/monitors/{monitor['id']}/copy",
            {},
            ADMIN_TOKEN,
        )
        self.assertEqual(201, status)
        copied_id = copied["monitor"]["id"]

        current_order = [monitor["id"], copied_id]
        status, reordered = self.request(
            "POST",
            "/api/v1/admin/monitors/reorder",
            {
                "expectedMonitorIds": current_order,
                "monitorIds": list(reversed(current_order)),
            },
            ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual(
            list(reversed(current_order)),
            [item["id"] for item in reordered["monitors"]],
        )

        status, monitors = self.request(
            "GET", "/api/v1/monitors", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual(
            list(reversed(current_order)),
            [item["id"] for item in monitors["monitors"]],
        )

        status, deleted = self.request(
            "DELETE", f"/api/v1/admin/monitors/{copied_id}", token=ADMIN_TOKEN
        )
        self.assertEqual(200, status)
        self.assertTrue(deleted["ok"])

        stored = json.loads(self.store_file.read_text(encoding="utf-8"))["monitors"]
        self.assertEqual([monitor["id"]], [item["id"] for item in stored])
        self.assertEqual(GROUP_ID, stored[0]["groupId"])

    def test_missing_group_is_rejected_when_multiple_groups_are_configured(self) -> None:
        self.config_file.write_text(
            json.dumps(
                {
                    "groups": [
                        {"id": GROUP_ID, "sharedToken": GROUP_TOKEN},
                        {"id": "other-team", "sharedToken": "other-secret-token"},
                    ],
                    "monitors": [],
                }
            ),
            encoding="utf-8",
        )
        self.assertTrue(self.manager.reload(force=True))

        requests = (
            ("GET", "/api/v1/status", None, GROUP_TOKEN),
            ("GET", "/api/v1/monitors", None, GROUP_TOKEN),
            ("POST", "/api/v1/clients/register", {"clientId": "owner"}, GROUP_TOKEN),
            ("POST", "/api/v1/alerts", {"senderId": "owner"}, GROUP_TOKEN),
            ("GET", "/api/v1/admin/monitors", None, ADMIN_TOKEN),
        )
        for method, path, body, token in requests:
            with self.subTest(method=method, path=path):
                status, response = self.request(method, path, body, token)
                self.assertEqual(400, status)
                self.assertEqual("group_required", response["error"]["code"])

        status, explicit = self.request(
            "POST",
            "/api/v1/clients/register",
            {"groupId": GROUP_ID, "clientId": "legacy", "clientName": "Legacy"},
            GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual("Legacy", explicit["client"]["name"])

    def test_cursor_token_poll_advances_and_legacy_poll_remains_compatible(self) -> None:
        encoded_group = urllib.parse.quote(GROUP_ID)
        self.register("receiver")
        first, _, _ = self.state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="sender",
            sender_name="Sender",
            message="first",
            excluded_client_id="sender",
        )

        status, legacy_poll = self.request(
            "GET",
            f"/api/v1/alerts?groupId={encoded_group}&clientId=receiver"
            "&after=0&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual([first], legacy_poll["alerts"])
        self.assertTrue(legacy_poll["cursorValid"])
        self.assertEqual(first["eventUid"], legacy_poll["cursorToken"])

        second, _, _ = self.state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="sender",
            sender_name="Sender",
            message="second",
            excluded_client_id="sender",
        )
        status, token_poll = self.request(
            "GET",
            f"/api/v1/alerts?groupId={encoded_group}&clientId=receiver"
            f"&after={first['id']}&afterToken={first['eventUid']}&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual([second], token_poll["alerts"])
        self.assertTrue(token_poll["cursorValid"])
        self.assertEqual(second["id"], token_poll["cursor"])
        self.assertEqual(second["eventUid"], token_poll["cursorToken"])
        self.assertEqual(second["id"], token_poll["headCursor"])
        self.assertEqual(second["eventUid"], token_poll["headEventUid"])

        status, sender_poll = self.request(
            "GET",
            f"/api/v1/alerts?groupId={encoded_group}&clientId=sender"
            "&after=0&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual([], sender_poll["alerts"])
        self.assertEqual(second["id"], sender_poll["cursor"])

    def test_cursor_token_detects_same_id_replacement_after_state_rollback(self) -> None:
        first, _, _ = self.state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="sender",
            sender_name="Sender",
            message="first",
            excluded_client_id="sender",
        )
        first_snapshot = self.data_file.read_text(encoding="utf-8")
        old_second, _, _ = self.state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="sender",
            sender_name="Sender",
            message="old second",
            excluded_client_id="sender",
        )

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
        self.assertEqual(old_second["id"], replacement["id"])
        self.assertNotEqual(old_second["eventUid"], replacement["eventUid"])

        result = rolled_back.poll_with_cursor_token(
            GROUP_ID,
            "receiver",
            old_second["id"],
            old_second["eventUid"],
            0,
        )
        events, cursor, cursor_token, valid, head_cursor, head_event_uid = result
        self.assertEqual([], events)
        self.assertEqual(0, cursor)
        self.assertIsNone(cursor_token)
        self.assertFalse(valid)
        self.assertEqual(replacement["id"], head_cursor)
        self.assertEqual(replacement["eventUid"], head_event_uid)

        legacy_events, legacy_cursor = rolled_back.poll(
            GROUP_ID, "receiver", first["id"], 0
        )
        self.assertEqual([replacement], legacy_events)
        self.assertEqual(replacement["id"], legacy_cursor)

    def test_alert_poll_rejects_malformed_after_tokens_and_resets_mismatch(self) -> None:
        event, _, _ = self.state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="sender",
            sender_name="Sender",
            message="wake up",
            excluded_client_id="sender",
        )
        encoded_group = urllib.parse.quote(GROUP_ID)
        base_path = (
            f"/api/v1/alerts?groupId={encoded_group}&clientId=receiver"
            f"&after={event['id']}&wait=0"
        )
        malformed_queries = (
            "&afterToken=",
            f"&afterToken={event['eventUid'].upper()}",
            "&afterToken=abc",
            f"&afterToken={event['eventUid']}&afterToken={event['eventUid']}",
        )
        for query_suffix in malformed_queries:
            with self.subTest(query_suffix=query_suffix):
                status, response = self.request(
                    "GET", base_path + query_suffix, token=GROUP_TOKEN
                )
                self.assertEqual(400, status)
                self.assertEqual("invalid_request", response["error"]["code"])

        mismatched_token = "0" * 32
        if mismatched_token == event["eventUid"]:
            mismatched_token = "1" * 32
        status, response = self.request(
            "GET", base_path + f"&afterToken={mismatched_token}", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual([], response["alerts"])
        self.assertEqual(0, response["cursor"])
        self.assertIsNone(response["cursorToken"])
        self.assertFalse(response["cursorValid"])
        self.assertEqual(event["id"], response["headCursor"])
        self.assertEqual(event["eventUid"], response["headEventUid"])
        self.assertEqual(self.state.server_instance_id, response["serverInstanceId"])

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

    def test_storage_failure_degrades_health_until_a_successful_persist(self) -> None:
        with mock.patch("relay_server.atomic_write_json", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.state.append_event(
                    group_id=GROUP_ID,
                    event_type="manual",
                    sender_id="sender",
                    sender_name="Sender",
                    message="not durable",
                )

        status, degraded = self.request("GET", "/health")
        self.assertEqual(503, status)
        self.assertEqual("degraded", degraded["status"])
        self.assertFalse(degraded["config"]["storage"]["healthy"])

        self.state.append_event(
            group_id=GROUP_ID,
            event_type="manual",
            sender_id="sender",
            sender_name="Sender",
            message="durable again",
        )
        status, recovered = self.request("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual("ok", recovered["status"])

    def test_storage_probe_is_rate_limited(self) -> None:
        with mock.patch("relay_server.time.monotonic", return_value=100.0):
            with mock.patch.object(self.state, "_perform_storage_probe") as probe:
                self.state._last_storage_probe_at = 0
                self.state.storage_status(probe=True)
                self.state.storage_status(probe=True)
                self.state.storage_status(probe=True)

        probe.assert_called_once_with(self.data_file)

    def test_storage_probe_does_not_hold_relay_lock_or_run_concurrently(self) -> None:
        probe_started = threading.Event()
        release_probe = threading.Event()

        def blocked_probe(_data_file: Path) -> None:
            probe_started.set()
            self.assertTrue(release_probe.wait(timeout=2))

        self.state._last_storage_probe_at = time.monotonic() - 1_000
        with mock.patch.object(
            self.state, "_perform_storage_probe", side_effect=blocked_probe
        ) as probe:
            probe_thread = threading.Thread(
                target=self.state.storage_status, kwargs={"probe": True}
            )
            probe_thread.start()
            try:
                self.assertTrue(probe_started.wait(timeout=1))

                acquired = self.state._condition.acquire(timeout=1)
                self.assertTrue(acquired, "storage I/O must run outside the relay lock")
                if acquired:
                    self.state._condition.release()

                second_status = self.state.storage_status(probe=True)
                self.assertTrue(second_status["healthy"])
                probe.assert_called_once_with(self.data_file)
            finally:
                release_probe.set()
                probe_thread.join(timeout=2)

        self.assertFalse(probe_thread.is_alive())
        probe.assert_called_once_with(self.data_file)

    def test_reload_rejects_empty_authentication_and_preserves_old_token(self) -> None:
        self.assertTrue(self.manager.authorize(GROUP_ID, GROUP_TOKEN))
        self.config_file.write_text("{}", encoding="utf-8")

        self.assertFalse(self.manager.reload(force=True))
        self.assertTrue(self.manager.authorize(GROUP_ID, GROUP_TOKEN))
        self.assertFalse(self.manager.authorize(GROUP_ID, "replacement-token"))
        config_status = self.manager.config_status()
        self.assertIn("no shared token configured", config_status["lastError"])

        status, health = self.request("GET", "/health")
        self.assertEqual(503, status)
        self.assertEqual("degraded", health["status"])
        self.assertIn("no shared token configured", health["config"]["lastError"])

    def test_reload_rejects_non_object_root_and_preserves_old_configuration(self) -> None:
        original_loaded_at = self.manager.config_status()["lastLoadedAt"]
        self.config_file.write_text("[]", encoding="utf-8")

        self.assertFalse(self.manager.reload(force=True))
        self.assertTrue(self.manager.authorize(GROUP_ID, GROUP_TOKEN))
        self.assertEqual(
            original_loaded_at, self.manager.config_status()["lastLoadedAt"]
        )
        self.assertIn(
            "configuration root must be a JSON object",
            self.manager.config_status()["lastError"],
        )

        status, health = self.request("GET", "/health")
        self.assertEqual(503, status)
        self.assertEqual("degraded", health["status"])
        self.assertIn(
            "configuration root must be a JSON object",
            health["config"]["lastError"],
        )

    def test_invalid_monitor_element_is_reported_without_raising(self) -> None:
        self.store_file.write_text(json.dumps({"monitors": [1]}), encoding="utf-8")

        self.assertFalse(self.manager.reload(force=True))
        status = self.manager.config_status()
        self.assertIn("every monitor must be an object", status["lastError"])

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

    def test_admin_crud_returns_secrets_and_legacy_mask_update_retains_them(self) -> None:
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
        self.assertEqual(create_body["body"], monitor["body"])
        self.assertEqual(
            json.loads(create_body["headersJson"]), json.loads(monitor["headersJson"])
        )
        self.assertEqual(create_body["url"], monitor["url"])
        self.assertFalse(monitor["urlMasked"])
        serialized = json.dumps(created)
        for secret in ("url-secret", "full", "header-secret", "header-key", "body-secret"):
            self.assertIn(secret, serialized)

        status, listed = self.request(
            "GET",
            f"/api/v1/admin/monitors?groupId={GROUP_ID}",
            token=ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        serialized = json.dumps(listed)
        for secret in ("url-secret", "full", "header-secret", "header-key", "body-secret"):
            self.assertIn(secret, serialized)

        update_body = {
            "groupId": GROUP_ID,
            "name": "Renamed balance",
            "url": (
                "https://example.invalid/balance?apiKey=__KEEP__&view=__KEEP__"
            ),
            "headersJson": json.dumps(
                {"Authorization": KEEP_PLACEHOLDER, "X-Api-Key": KEEP_PLACEHOLDER}
            ),
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

    def test_admin_copy_returns_secrets_while_runtime_status_stays_masked(self) -> None:
        source_body = self.monitor_request(
            "source-balance",
            "Private balance",
            url=(
                "https://private-user:private-password@example.invalid/balance"
                "?apiKey=url-secret&view=full#private-fragment"
            ),
            method="POST",
            headersJson=json.dumps(
                {"Authorization": "Bearer header-secret", "X-Api-Key": "header-key"}
            ),
            body={"account": "main", "token": "body-secret"},
            enabled=True,
        )
        status, created = self.request(
            "POST", "/api/v1/admin/monitors", source_body, ADMIN_TOKEN
        )
        self.assertEqual(201, status)
        source = created["monitor"]

        status, unauthorized = self.request(
            "POST",
            "/api/v1/admin/monitors/source-balance/copy",
            {"groupId": GROUP_ID},
            GROUP_TOKEN,
        )
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", unauthorized["error"]["code"])

        status, first_response = self.request(
            "POST",
            "/api/v1/admin/monitors/source-balance/copy",
            {"groupId": GROUP_ID},
            ADMIN_TOKEN,
        )
        self.assertEqual(201, status)
        first_copy = first_response["monitor"]
        self.assertNotEqual(source["id"], first_copy["id"])
        self.assertEqual("Private balance（副本）", first_copy["name"])
        self.assertFalse(first_copy["enabled"])
        self.assertEqual(1, first_copy["revision"])
        self.assertGreater(first_copy["updatedAt"], 0)
        self.assertEqual(source_body["body"], first_copy["body"])
        self.assertEqual(
            json.loads(source_body["headersJson"]),
            json.loads(first_copy["headersJson"]),
        )
        self.assertEqual(source_body["url"], first_copy["url"])

        status, second_response = self.request(
            "POST",
            "/api/v1/admin/monitors/source-balance/copy",
            {"groupId": GROUP_ID},
            ADMIN_TOKEN,
        )
        self.assertEqual(201, status)
        second_copy = second_response["monitor"]
        self.assertNotIn(second_copy["id"], {source["id"], first_copy["id"]})
        self.assertEqual("Private balance（副本2）", second_copy["name"])
        self.assertFalse(second_copy["enabled"])

        status, listed = self.request(
            "GET",
            f"/api/v1/admin/monitors?groupId={GROUP_ID}",
            token=ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        expected_order = [source["id"], second_copy["id"], first_copy["id"]]
        self.assertEqual(expected_order, [item["id"] for item in listed["monitors"]])

        stored = json.loads(self.store_file.read_text(encoding="utf-8"))["monitors"]
        stored_by_id = {item["id"]: item for item in stored}
        for copied_id in (first_copy["id"], second_copy["id"]):
            copied = stored_by_id[copied_id]
            self.assertEqual(source_body["url"], copied["url"])
            self.assertEqual(json.loads(source_body["headersJson"]), copied["headers"])
            self.assertEqual(source_body["body"], copied["body"])
            self.assertFalse(copied["enabled"])

        status, runtime = self.request(
            "GET", f"/api/v1/monitors?groupId={GROUP_ID}", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual(expected_order, [item["id"] for item in runtime["monitors"]])
        self.assertEqual(
            ["POST", "POST", "POST"],
            [item["method"] for item in runtime["monitors"]],
        )
        self.assertTrue(
            all(KEEP_PLACEHOLDER in item["url"] for item in runtime["monitors"])
        )

        admin_documents = json.dumps(
            {"copies": [first_response, second_response], "listed": listed}
        )
        public_documents = json.dumps(runtime)
        for secret in (
            "private-user",
            "private-password",
            "url-secret",
            "full",
            "private-fragment",
            "header-secret",
            "header-key",
            "body-secret",
        ):
            self.assertIn(secret, admin_documents)
            self.assertNotIn(secret, public_documents)

    def test_admin_reorder_is_conflict_safe_and_persists_array_order(self) -> None:
        other_group = "other-team"
        other_token = "other-secret-token"
        self.config_file.write_text(
            json.dumps(
                {
                    "groups": [
                        {"id": GROUP_ID, "sharedToken": GROUP_TOKEN},
                        {"id": other_group, "sharedToken": other_token},
                    ],
                    "monitors": [],
                }
            ),
            encoding="utf-8",
        )
        self.assertTrue(self.manager.reload(force=True))

        documents = [
            self.monitor_request("monitor-a", "Zulu"),
            self.monitor_request("other-x", "Other X", other_group),
            self.monitor_request("monitor-b", "Alpha"),
            self.monitor_request("other-y", "Other Y", other_group),
            self.monitor_request("monitor-c", "Middle"),
        ]
        for document in documents:
            status, _ = self.request(
                "POST", "/api/v1/admin/monitors", document, ADMIN_TOKEN
            )
            self.assertEqual(201, status)

        original_group_order = ["monitor-a", "monitor-b", "monitor-c"]
        original_store_order = [
            "monitor-a", "other-x", "monitor-b", "other-y", "monitor-c"
        ]
        status, listed = self.request(
            "GET",
            f"/api/v1/admin/monitors?groupId={GROUP_ID}",
            token=ADMIN_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual(
            original_group_order, [item["id"] for item in listed["monitors"]]
        )
        self.assertEqual(
            original_store_order,
            [
                item["id"]
                for item in json.loads(self.store_file.read_text(encoding="utf-8"))[
                    "monitors"
                ]
            ],
        )

        request_body = {
            "groupId": GROUP_ID,
            "expectedMonitorIds": original_group_order,
            "monitorIds": ["monitor-c", "monitor-a", "monitor-b"],
        }
        status, unauthorized = self.request(
            "POST", "/api/v1/admin/monitors/reorder", request_body, GROUP_TOKEN
        )
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", unauthorized["error"]["code"])

        invalid_bodies = (
            {**request_body, "expectedMonitorIds": ["monitor-b", "monitor-a", "monitor-c"]},
            {**request_body, "monitorIds": ["monitor-c", "monitor-a"]},
        )
        for invalid_body in invalid_bodies:
            with self.subTest(body=invalid_body):
                status, conflict = self.request(
                    "POST",
                    "/api/v1/admin/monitors/reorder",
                    invalid_body,
                    ADMIN_TOKEN,
                )
                self.assertEqual(409, status)
                self.assertEqual("order_conflict", conflict["error"]["code"])

        status, invalid = self.request(
            "POST",
            "/api/v1/admin/monitors/reorder",
            {**request_body, "monitorIds": ["monitor-a", "monitor-a", "monitor-c"]},
            ADMIN_TOKEN,
        )
        self.assertEqual(400, status)
        self.assertEqual("invalid_request", invalid["error"]["code"])
        self.assertEqual(
            original_store_order,
            [
                item["id"]
                for item in json.loads(self.store_file.read_text(encoding="utf-8"))[
                    "monitors"
                ]
            ],
        )

        self.manager._statuses[(GROUP_ID, "monitor-a")].last_value = 12.5
        self.manager._statuses[(GROUP_ID, "monitor-a")].last_check_at = 123
        status, reordered = self.request(
            "POST", "/api/v1/admin/monitors/reorder", request_body, ADMIN_TOKEN
        )
        self.assertEqual(200, status)
        reordered_group = ["monitor-c", "monitor-a", "monitor-b"]
        self.assertEqual(
            reordered_group, [item["id"] for item in reordered["monitors"]]
        )

        expected_store_order = [
            "monitor-c", "other-x", "monitor-a", "other-y", "monitor-b"
        ]
        stored_ids = [
            item["id"]
            for item in json.loads(self.store_file.read_text(encoding="utf-8"))["monitors"]
        ]
        self.assertEqual(expected_store_order, stored_ids)
        self.assertEqual(
            ["other-x", "other-y"],
            [item["id"] for item in self.manager.list_monitor_configs(other_group)],
        )

        status, runtime = self.request(
            "GET", f"/api/v1/monitors?groupId={GROUP_ID}", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        self.assertEqual(
            reordered_group, [item["id"] for item in runtime["monitors"]]
        )
        retained_status = next(
            item for item in runtime["monitors"] if item["id"] == "monitor-a"
        )
        self.assertEqual(12.5, retained_status["lastValue"])
        self.assertEqual(123, retained_status["lastCheckAt"])

        status, stale = self.request(
            "POST", "/api/v1/admin/monitors/reorder", request_body, ADMIN_TOKEN
        )
        self.assertEqual(409, status)
        self.assertEqual("order_conflict", stale["error"]["code"])

        self.assertTrue(self.manager.reload(force=True))
        self.assertEqual(
            reordered_group,
            [item["id"] for item in self.manager.list_monitor_configs(GROUP_ID)],
        )
        reloaded_manager = MonitorManager(
            RelayState(None), self.config_file, managed_store_file=self.store_file
        )
        try:
            self.assertEqual(
                reordered_group,
                [item["id"] for item in reloaded_manager.list_monitor_configs(GROUP_ID)],
            )
            self.assertEqual(
                reordered_group,
                [item["id"] for item in reloaded_manager.statuses_for_group(GROUP_ID)],
            )
        finally:
            reloaded_manager.stop()

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

            registration = self.register("owner")
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

            status, monitor_status = self.request(
                "GET",
                f"/api/v1/monitors?groupId={GROUP_ID}",
                token=GROUP_TOKEN,
            )
            self.assertEqual(200, status)
            self.assertTrue(monitor_status["monitors"][0]["breached"])
            self.assertEqual(5, monitor_status["monitors"][0]["lastValue"])
            self.assertEqual(50, monitor_status["monitors"][0]["lastRawValue"])
            self.assertEqual(200, monitor_status["monitors"][0]["lastHttpCode"])
            self.assertGreater(monitor_status["monitors"][0]["lastCheckAt"], 0)
            self.assertEqual(
                monitor_status["monitors"][0]["lastCheckAt"],
                monitor_status["monitors"][0]["lastSuccessAt"],
            )

            status, alerts = self.request(
                "GET",
                f"/api/v1/alerts?groupId={GROUP_ID}&clientId=owner"
                f"&after={registration['cursor']}&wait=0",
                token=GROUP_TOKEN,
            )
            self.assertEqual(200, status)
            self.assertEqual([], alerts["alerts"], "manual test must not emit an alert")

            self.manager.check_monitor(GROUP_ID, "main-balance")
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

    def test_admin_refresh_all_enabled_monitors_updates_shared_snapshots(self) -> None:
        for monitor_id, enabled in (
            ("refresh-a", True),
            ("refresh-b", True),
            ("refresh-disabled", False),
        ):
            self.manager.create_monitor_config(
                self.monitor_request(
                    monitor_id,
                    monitor_id,
                    enabled=enabled,
                    threshold=10,
                )
            )

        status, unauthorized = self.request(
            "POST", "/api/v1/admin/monitors/refresh", {}, GROUP_TOKEN
        )
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", unauthorized["error"]["code"])

        registration = self.register("refresh-owner")
        original_next_checks = {
            monitor_id: monitor_status.next_check_at
            for (group_id, monitor_id), monitor_status in self.manager._statuses.items()
            if group_id == GROUP_ID
        }
        values = {
            "refresh-a": (200, 50.0, 5.0),
            "refresh-b": (204, 250.0, 25.0),
        }

        def fetch(definition: object) -> tuple[int, float, float]:
            return values[definition.id]  # type: ignore[attr-defined]

        with mock.patch.object(self.manager, "_fetch_value", side_effect=fetch):
            status, refreshed = self.request(
                "POST", "/api/v1/admin/monitors/refresh", {}, ADMIN_TOKEN
            )

        self.assertEqual(200, status)
        self.assertTrue(refreshed["ok"])
        self.assertEqual(2, refreshed["total"])
        self.assertEqual(2, refreshed["succeeded"])
        self.assertEqual(0, refreshed["failed"])
        self.assertEqual(
            ["refresh-a", "refresh-b"],
            [item["monitorId"] for item in refreshed["results"]],
        )
        self.assertTrue(all(item["ok"] for item in refreshed["results"]))

        status, shared = self.request("GET", "/api/v1/monitors", token=GROUP_TOKEN)
        self.assertEqual(200, status)
        shared_by_id = {item["id"]: item for item in shared["monitors"]}
        self.assertEqual(5.0, shared_by_id["refresh-a"]["lastValue"])
        self.assertEqual(200, shared_by_id["refresh-a"]["lastHttpCode"])
        self.assertTrue(shared_by_id["refresh-a"]["breached"])
        self.assertEqual(25.0, shared_by_id["refresh-b"]["lastValue"])
        self.assertEqual(204, shared_by_id["refresh-b"]["lastHttpCode"])
        self.assertFalse(shared_by_id["refresh-b"]["breached"])
        self.assertEqual(0, shared_by_id["refresh-disabled"]["lastCheckAt"])
        for monitor_id in ("refresh-a", "refresh-b", "refresh-disabled"):
            self.assertEqual(
                original_next_checks[monitor_id],
                self.manager._statuses[(GROUP_ID, monitor_id)].next_check_at,
            )

        status, alerts = self.request(
            "GET",
            "/api/v1/alerts?clientId=refresh-owner"
            f"&after={registration['cursor']}&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual([], alerts["alerts"])

    def test_admin_refresh_continues_after_sanitized_monitor_failure(self) -> None:
        secret_values = (
            "url-refresh-secret",
            "header-refresh-secret",
            "body-refresh-secret",
        )
        self.manager.create_monitor_config(
            self.monitor_request(
                "refresh-failed",
                "Failed refresh",
                url=f"https://example.invalid/value?token={secret_values[0]}",
                headersJson=json.dumps({"Authorization": secret_values[1]}),
                body={"token": secret_values[2]},
                enabled=True,
            )
        )
        self.manager.create_monitor_config(
            self.monitor_request("refresh-continued", "Continued refresh", enabled=True)
        )
        called: list[str] = []

        def fetch(definition: object) -> tuple[int, float, float]:
            monitor_id = definition.id  # type: ignore[attr-defined]
            called.append(monitor_id)
            if monitor_id == "refresh-failed":
                raise MonitorFetchError("upstream unavailable", 503)
            return 200, 42.0, 42.0

        with mock.patch.object(self.manager, "_fetch_value", side_effect=fetch):
            status, refreshed = self.request(
                "POST", "/api/v1/admin/monitors/refresh", {}, ADMIN_TOKEN
            )

        self.assertEqual(200, status)
        self.assertFalse(refreshed["ok"])
        self.assertEqual(2, refreshed["total"])
        self.assertEqual(1, refreshed["succeeded"])
        self.assertEqual(1, refreshed["failed"])
        self.assertEqual({"refresh-failed", "refresh-continued"}, set(called))
        failed, continued = refreshed["results"]
        self.assertEqual("refresh-failed", failed["monitorId"])
        self.assertFalse(failed["ok"])
        self.assertEqual("monitor_test_failed", failed["error"]["code"])
        self.assertEqual(503, failed["error"]["httpCode"])
        self.assertEqual("refresh-continued", continued["monitorId"])
        self.assertTrue(continued["ok"])
        serialized = json.dumps(refreshed)
        for secret in secret_values:
            self.assertNotIn(secret, serialized)

        status, shared = self.request("GET", "/api/v1/monitors", token=GROUP_TOKEN)
        self.assertEqual(200, status)
        shared_by_id = {item["id"]: item for item in shared["monitors"]}
        self.assertEqual(0, shared_by_id["refresh-failed"]["lastCheckAt"])
        self.assertEqual(0, shared_by_id["refresh-failed"]["consecutiveErrors"])
        self.assertEqual(42.0, shared_by_id["refresh-continued"]["lastValue"])
        self.assertGreater(shared_by_id["refresh-continued"]["lastCheckAt"], 0)

    def test_admin_refresh_does_not_consume_first_automatic_breach_alert(self) -> None:
        self.manager.create_monitor_config(
            self.monitor_request(
                "refresh-breach",
                "Refresh breach",
                enabled=True,
                threshold=10,
                repeatAlertSeconds=0,
            )
        )
        registration = self.register("refresh-breach-owner")

        with mock.patch.object(
            self.manager, "_fetch_value", return_value=(200, 5.0, 5.0)
        ):
            refreshed = self.manager.refresh_monitor_configs(GROUP_ID)
            self.manager.check_monitor(GROUP_ID, "refresh-breach")

        self.assertTrue(refreshed["ok"])
        self.assertTrue(refreshed["results"][0]["result"]["breached"])
        shared = self.manager.statuses_for_group(GROUP_ID)[0]
        self.assertTrue(shared["breached"])

        status, alerts = self.request(
            "GET",
            "/api/v1/alerts?clientId=refresh-breach-owner"
            f"&after={registration['cursor']}&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual(["monitor_breach"], [item["type"] for item in alerts["alerts"]])

    def test_admin_refresh_success_does_not_reset_automatic_error_threshold(self) -> None:
        self.manager.create_monitor_config(
            self.monitor_request(
                "refresh-errors",
                "Refresh errors",
                enabled=True,
                errorThreshold=2,
                repeatAlertSeconds=0,
            )
        )
        registration = self.register("refresh-error-owner")

        with mock.patch.object(
            self.manager,
            "_fetch_value",
            side_effect=MonitorFetchError("first scheduled failure", 503),
        ):
            self.manager.check_monitor(GROUP_ID, "refresh-errors")

        with mock.patch.object(
            self.manager, "_fetch_value", return_value=(200, 20.0, 20.0)
        ):
            refreshed = self.manager.refresh_monitor_configs(GROUP_ID)

        self.assertTrue(refreshed["ok"])
        shared = self.manager.statuses_for_group(GROUP_ID)[0]
        self.assertEqual(0, shared["consecutiveErrors"])
        self.assertEqual("", shared["lastError"])

        with mock.patch.object(
            self.manager,
            "_fetch_value",
            side_effect=MonitorFetchError("second scheduled failure", 503),
        ):
            self.manager.check_monitor(GROUP_ID, "refresh-errors")

        status, alerts = self.request(
            "GET",
            "/api/v1/alerts?clientId=refresh-error-owner"
            f"&after={registration['cursor']}&wait=0",
            token=GROUP_TOKEN,
        )
        self.assertEqual(200, status)
        self.assertEqual(["monitor_error"], [item["type"] for item in alerts["alerts"]])

    def test_repeated_admin_refresh_fails_fast_without_duplicate_checks(self) -> None:
        self.manager.create_monitor_config(
            self.monitor_request("slow-refresh", "Slow refresh", enabled=True)
        )
        entered = threading.Event()
        release = threading.Event()
        fetch_calls = 0
        fetch_lock = threading.Lock()

        def fetch(_definition: object) -> tuple[int, float, float]:
            nonlocal fetch_calls
            with fetch_lock:
                fetch_calls += 1
            entered.set()
            self.assertTrue(release.wait(timeout=3))
            return 200, 20.0, 20.0

        with mock.patch.object(self.manager, "_fetch_value", side_effect=fetch):
            with ThreadPoolExecutor(max_workers=1) as requests:
                first_request = requests.submit(
                    self.request,
                    "POST",
                    "/api/v1/admin/monitors/refresh",
                    {},
                    ADMIN_TOKEN,
                    5,
                )
                self.assertTrue(entered.wait(timeout=2))
                started_at = time.monotonic()
                try:
                    second_status, second_body = self.request(
                        "POST",
                        "/api/v1/admin/monitors/refresh",
                        {},
                        ADMIN_TOKEN,
                        timeout=2,
                    )
                finally:
                    release.set()
                elapsed = time.monotonic() - started_at
                first_status, first_body = first_request.result(timeout=3)

        self.assertEqual(409, second_status)
        self.assertEqual("refresh_in_progress", second_body["error"]["code"])
        self.assertLess(elapsed, 1.0)
        self.assertEqual(200, first_status)
        self.assertTrue(first_body["ok"])
        self.assertEqual(1, fetch_calls)

    def test_admin_refresh_does_not_overlap_scheduler_check(self) -> None:
        self.manager.create_monitor_config(
            self.monitor_request("scheduled-busy", "Scheduled busy", enabled=True)
        )
        entered = threading.Event()
        release = threading.Event()
        fetch_calls = 0

        def fetch(_definition: object) -> tuple[int, float, float]:
            nonlocal fetch_calls
            fetch_calls += 1
            entered.set()
            self.assertTrue(release.wait(timeout=3))
            return 200, 20.0, 20.0

        with mock.patch.object(self.manager, "_fetch_value", side_effect=fetch):
            with self.manager._lock:
                self.manager._statuses[(GROUP_ID, "scheduled-busy")].next_check_at = 0
            self.manager.start()
            self.assertTrue(entered.wait(timeout=2))
            try:
                refreshed = self.manager.refresh_monitor_configs(GROUP_ID)
            finally:
                release.set()

            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                with self.manager._lock:
                    if (GROUP_ID, "scheduled-busy") not in self.manager._in_flight:
                        break
                time.sleep(0.01)

        self.assertFalse(refreshed["ok"])
        self.assertEqual("monitor_busy", refreshed["results"][0]["error"]["code"])
        self.assertEqual(1, fetch_calls)
        with self.manager._lock:
            self.assertNotIn((GROUP_ID, "scheduled-busy"), self.manager._in_flight)

    def test_admin_refresh_rejects_result_when_monitor_is_edited_mid_check(self) -> None:
        monitor = self.manager.create_monitor_config(
            self.monitor_request("edited-refresh", "Edited refresh", enabled=True)
        )
        entered = threading.Event()
        release = threading.Event()

        def fetch(_definition: object) -> tuple[int, float, float]:
            entered.set()
            self.assertTrue(release.wait(timeout=3))
            return 200, 5.0, 5.0

        with mock.patch.object(self.manager, "_fetch_value", side_effect=fetch):
            with ThreadPoolExecutor(max_workers=1) as requests:
                pending = requests.submit(self.manager.refresh_monitor_configs, GROUP_ID)
                self.assertTrue(entered.wait(timeout=2))
                self.manager.update_monitor_config(
                    GROUP_ID,
                    "edited-refresh",
                    {"name": "Edited while checking", "revision": monitor["revision"]},
                )
                release.set()
                refreshed = pending.result(timeout=3)

        self.assertFalse(refreshed["ok"])
        self.assertEqual("monitor_changed", refreshed["results"][0]["error"]["code"])
        shared = self.manager.statuses_for_group(GROUP_ID)[0]
        self.assertEqual("Edited while checking", shared["name"])
        self.assertEqual(0, shared["lastCheckAt"])

    def test_admin_refresh_rejects_result_when_monitor_is_deleted_mid_check(self) -> None:
        self.manager.create_monitor_config(
            self.monitor_request("deleted-refresh", "Deleted refresh", enabled=True)
        )
        entered = threading.Event()
        release = threading.Event()

        def fetch(_definition: object) -> tuple[int, float, float]:
            entered.set()
            self.assertTrue(release.wait(timeout=3))
            return 200, 5.0, 5.0

        with mock.patch.object(self.manager, "_fetch_value", side_effect=fetch):
            with ThreadPoolExecutor(max_workers=1) as requests:
                pending = requests.submit(self.manager.refresh_monitor_configs, GROUP_ID)
                self.assertTrue(entered.wait(timeout=2))
                self.manager.delete_monitor_config(GROUP_ID, "deleted-refresh")
                release.set()
                refreshed = pending.result(timeout=3)

        self.assertFalse(refreshed["ok"])
        self.assertEqual("monitor_changed", refreshed["results"][0]["error"]["code"])
        self.assertEqual([], self.manager.statuses_for_group(GROUP_ID))

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

        status, status_after_test_failure = self.request(
            "GET", "/api/v1/monitors", token=GROUP_TOKEN
        )
        self.assertEqual(200, status)
        failed_monitor = status_after_test_failure["monitors"][0]
        self.assertEqual(0, failed_monitor["lastCheckAt"])
        self.assertEqual(0, failed_monitor["consecutiveErrors"])
        self.assertEqual("", failed_monitor["lastError"])

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
