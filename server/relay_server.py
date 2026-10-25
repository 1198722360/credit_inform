#!/usr/bin/env python3
"""HTTP relay and endpoint monitor for CreditInform."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import math
import os
import re
import signal
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from copy import deepcopy
from concurrent.futures import CancelledError, Future, ThreadPoolExecutor
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation, localcontext
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import gateway_client


VERSION = "1.5.0"
MAX_REQUEST_BYTES = 16 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
KEEP_PLACEHOLDER = "__KEEP__"
ADMIN_PAGE_FILE = Path(__file__).with_name("admin_web.html")
STORAGE_PROBE_INTERVAL_SECONDS = 15.0


def now_ms() -> int:
    return int(time.time() * 1000)


def require_identifier(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER_RE.fullmatch(value):
        raise ValueError(
            f"{field_name} must contain 1-64 letters, numbers, '.', '_' or '-'"
        )
    return value


def require_text(value: Any, field_name: str, max_length: int, default: str | None = None) -> str:
    if value is None and default is not None:
        value = default
    if not isinstance(value, str):
        raise ValueError(f"{field_name} must be a string")
    value = value.strip()
    if not value or len(value) > max_length:
        raise ValueError(f"{field_name} must contain 1-{max_length} characters")
    return value


def finite_number(value: Any, field_name: str) -> float:
    if isinstance(value, bool):
        raise ValueError(f"{field_name} must be a finite number")
    try:
        result = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{field_name} must be a finite number") from error
    if not math.isfinite(result):
        raise ValueError(f"{field_name} must be a finite number")
    return result


def atomic_write_json(path: Path, document: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(document, output, ensure_ascii=False, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_name, path)
        fsync_directory(path.parent)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def fsync_directory(path: Path) -> None:
    if os.name == "nt":
        # Windows cannot open directory handles via os.open; the file itself
        # has already been flushed before its atomic replacement.
        return
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def parse_headers_document(item: dict[str, Any], prefix: str) -> dict[str, str]:
    if "headersJson" in item:
        raw_headers = item.get("headersJson")
        if not isinstance(raw_headers, str):
            raise ValueError(f"{prefix}.headersJson must be a JSON object string")
        try:
            headers_document = json.loads(raw_headers or "{}")
        except json.JSONDecodeError as error:
            raise ValueError(f"{prefix}.headersJson is not valid JSON: {error}") from error
    else:
        headers_document = item.get("headers", {})
    if not isinstance(headers_document, dict) or not all(
        isinstance(key, str) and isinstance(value, str)
        for key, value in headers_document.items()
    ):
        raise ValueError(f"{prefix}.headers must be an object of string values")
    return dict(headers_document)


def mask_url(url: str) -> tuple[str, bool]:
    parsed = urllib.parse.urlsplit(url)
    masked = bool(parsed.fragment)
    hostname = parsed.hostname or ""
    if parsed.port:
        hostname = f"{hostname}:{parsed.port}"
    if parsed.username is not None or parsed.password is not None:
        hostname = f"{KEEP_PLACEHOLDER}@{hostname}"
        masked = True
    query_items = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    # Query names are useful when editing, but every value is treated as a secret.
    safe_query = [(key, KEEP_PLACEHOLDER) for key, _value in query_items]
    masked = masked or bool(query_items)
    return (
        urllib.parse.urlunsplit(
            (parsed.scheme, hostname, parsed.path, urllib.parse.urlencode(safe_query), "")
        ),
        masked,
    )


def restore_masked_url(candidate: str, previous: str) -> str:
    if KEEP_PLACEHOLDER not in candidate:
        return candidate
    candidate_parts = urllib.parse.urlsplit(candidate)
    previous_parts = urllib.parse.urlsplit(previous)
    candidate_items = urllib.parse.parse_qsl(candidate_parts.query, keep_blank_values=True)
    previous_values: dict[str, list[str]] = {}
    for key, value in urllib.parse.parse_qsl(previous_parts.query, keep_blank_values=True):
        previous_values.setdefault(key, []).append(value)
    used: dict[str, int] = {}
    restored_items: list[tuple[str, str]] = []
    for key, value in candidate_items:
        position = used.get(key, 0)
        used[key] = position + 1
        if value == KEEP_PLACEHOLDER:
            values = previous_values.get(key, [])
            if position >= len(values):
                raise ValueError(f"url placeholder has no previous value for query field '{key}'")
            value = values[position]
        restored_items.append((key, value))
    netloc = candidate_parts.netloc
    if KEEP_PLACEHOLDER in netloc:
        if previous_parts.username is None and previous_parts.password is None:
            raise ValueError("url placeholder has no previous user information")
        netloc = previous_parts.netloc
    return urllib.parse.urlunsplit(
        (
            candidate_parts.scheme,
            netloc,
            candidate_parts.path,
            urllib.parse.urlencode(restored_items),
            candidate_parts.fragment,
        )
    )


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class RelayState:
    """Thread-safe client registry, persistent event log and long-poll condition."""

    def __init__(
        self,
        data_file: str | Path | None,
        heartbeat_timeout_seconds: int = 90,
        event_retention_seconds: int = 86_400,
        max_events_per_group: int = 1_000,
    ) -> None:
        self.data_file = Path(data_file) if data_file else None
        self.heartbeat_timeout_ms = heartbeat_timeout_seconds * 1000
        self.event_retention_ms = event_retention_seconds * 1000
        self.max_events_per_group = max_events_per_group
        self._condition = threading.Condition(threading.RLock())
        self._clients: dict[tuple[str, str], dict[str, Any]] = {}
        self._events: dict[str, list[dict[str, Any]]] = {}
        self._next_event_id = 1
        self._head_event_uid: str | None = None
        self._server_instance_id = uuid.uuid4().hex
        self._last_storage_error = ""
        self._last_storage_probe_error = ""
        self._last_persisted_at = 0
        self._storage_probe_lock = threading.Lock()
        self._storage_probe_in_flight = False
        self._last_storage_probe_at = float("-inf")
        self._load()
        # Write the identity before clients connect, including for a new or legacy state file.
        self._persist_locked()

    def _load(self) -> None:
        if self.data_file is None or not self.data_file.exists():
            return
        try:
            document = json.loads(self.data_file.read_text(encoding="utf-8"))
            if not isinstance(document, dict):
                raise ValueError("relay state root must be an object")
            events = document.get("events", [])
            if not isinstance(events, list):
                raise ValueError("events must be an array")
            seen_event_ids: set[int] = set()
            seen_event_uids: set[str] = set()
            for event in events:
                if not isinstance(event, dict):
                    raise ValueError("every relay event must be an object")
                group_id = event.get("groupId")
                event_id = event.get("id")
                if not isinstance(group_id, str) or not IDENTIFIER_RE.fullmatch(group_id):
                    raise ValueError("relay event contains an invalid groupId")
                if not isinstance(event_id, int) or isinstance(event_id, bool) or event_id <= 0:
                    raise ValueError("relay event contains an invalid id")
                if event_id in seen_event_ids:
                    raise ValueError(f"duplicate relay event id: {event_id}")
                event_uid = event.get("eventUid")
                if event_uid is None:
                    event_uid = uuid.uuid4().hex
                    event["eventUid"] = event_uid
                if not isinstance(event_uid, str) or not re.fullmatch(
                    r"[0-9a-f]{32}", event_uid
                ):
                    raise ValueError("relay event contains an invalid eventUid")
                if event_uid in seen_event_uids:
                    raise ValueError(f"duplicate relay eventUid: {event_uid}")
                seen_event_ids.add(event_id)
                seen_event_uids.add(event_uid)
                self._events.setdefault(group_id, []).append(event)
                self._next_event_id = max(self._next_event_id, event_id + 1)
            for group_events in self._events.values():
                group_events.sort(key=lambda item: item["id"])
            configured_next = document.get("nextEventId")
            if configured_next is not None and (
                not isinstance(configured_next, int)
                or isinstance(configured_next, bool)
                or configured_next <= 0
            ):
                raise ValueError("nextEventId must be a positive integer")
            if isinstance(configured_next, int):
                self._next_event_id = max(self._next_event_id, configured_next)
            configured_head_uid = document.get("headEventUid")
            if configured_head_uid is not None and (
                not isinstance(configured_head_uid, str)
                or not re.fullmatch(r"[0-9a-f]{32}", configured_head_uid)
            ):
                raise ValueError("headEventUid must be a 32-character lowercase hex string")
            if self._next_event_id > 1:
                self._head_event_uid = configured_head_uid
                if self._head_event_uid is None:
                    head_id = self._next_event_id - 1
                    self._head_event_uid = next(
                        (
                            event["eventUid"]
                            for group_events in self._events.values()
                            for event in group_events
                            if event["id"] == head_id
                        ),
                        uuid.uuid4().hex,
                    )
            configured_instance_id = document.get("serverInstanceId")
            if isinstance(configured_instance_id, str) and re.fullmatch(
                r"[0-9a-f]{32}", configured_instance_id
            ):
                self._server_instance_id = configured_instance_id
            self._cleanup_events_locked(now_ms())
        except (OSError, ValueError, json.JSONDecodeError) as error:
            raise RuntimeError(f"could not load relay state {self.data_file}: {error}") from error

    def _persist_locked(self) -> None:
        if self.data_file is None:
            return
        payload = {
            "serverInstanceId": self._server_instance_id,
            "nextEventId": self._next_event_id,
            "headEventUid": self._head_event_uid,
            "events": [
                event
                for group_events in self._events.values()
                for event in group_events
            ],
        }
        try:
            atomic_write_json(self.data_file, payload)
        except Exception as error:
            self._last_storage_error = (
                f"relay event storage write failed ({type(error).__name__})"
            )
            raise
        else:
            self._last_storage_error = ""
            self._last_storage_probe_error = ""
            self._last_persisted_at = now_ms()

    @staticmethod
    def _perform_storage_probe(data_file: Path) -> None:
        temporary_name = ""
        try:
            data_file.parent.mkdir(parents=True, exist_ok=True)
            descriptor, temporary_name = tempfile.mkstemp(
                prefix=".relay-storage-probe.", dir=str(data_file.parent)
            )
            with os.fdopen(descriptor, "wb") as output:
                output.write(b"ok")
                output.flush()
                os.fsync(output.fileno())
            os.unlink(temporary_name)
            temporary_name = ""
            fsync_directory(data_file.parent)
        finally:
            if temporary_name and os.path.exists(temporary_name):
                os.unlink(temporary_name)

    def _maybe_probe_storage(self) -> None:
        with self._condition:
            data_file = self.data_file
            persist_error = self._last_storage_error
        if data_file is None or persist_error:
            return

        monotonic_now = time.monotonic()
        with self._storage_probe_lock:
            if self._storage_probe_in_flight or (
                monotonic_now - self._last_storage_probe_at
                < STORAGE_PROBE_INTERVAL_SECONDS
            ):
                return
            self._storage_probe_in_flight = True

        probe_error = ""
        try:
            try:
                self._perform_storage_probe(data_file)
            except Exception as error:
                probe_error = (
                    f"relay event storage probe failed ({type(error).__name__})"
                )
            with self._condition:
                self._last_storage_probe_error = probe_error
        finally:
            with self._storage_probe_lock:
                self._last_storage_probe_at = time.monotonic()
                self._storage_probe_in_flight = False

    def storage_status(self, probe: bool = False) -> dict[str, Any]:
        if probe:
            self._maybe_probe_storage()
        with self._condition:
            enabled = self.data_file is not None
            error = self._last_storage_error or self._last_storage_probe_error
            if not enabled:
                error = "relay event persistence is disabled"
            return {
                "enabled": enabled,
                "healthy": enabled and not error,
                "lastPersistedAt": self._last_persisted_at,
                "lastError": error,
            }

    @property
    def server_instance_id(self) -> str:
        return self._server_instance_id

    def _cleanup_events_locked(self, current_time: int) -> bool:
        cutoff = current_time - self.event_retention_ms
        changed = False
        for group_id in list(self._events):
            old_events = self._events[group_id]
            retained = [event for event in old_events if event.get("createdAt", 0) >= cutoff]
            if len(retained) > self.max_events_per_group:
                retained = retained[-self.max_events_per_group :]
            if len(retained) != len(old_events):
                changed = True
            if retained:
                self._events[group_id] = retained
            else:
                del self._events[group_id]
        return changed

    def _touch_client_locked(self, group_id: str, client_id: str, client_name: str) -> dict[str, Any]:
        timestamp = now_ms()
        record = {
            "groupId": group_id,
            "id": client_id,
            "name": client_name,
            "lastSeenAt": timestamp,
        }
        self._clients[(group_id, client_id)] = record
        return record.copy()

    def register(self, group_id: str, client_id: str, client_name: str) -> tuple[dict[str, Any], int]:
        with self._condition:
            client = self._touch_client_locked(group_id, client_id, client_name)
            return client, self._current_cursor_locked(group_id)

    def heartbeat(self, group_id: str, client_id: str, client_name: str) -> tuple[dict[str, Any], int]:
        return self.register(group_id, client_id, client_name)

    def _current_cursor_locked(self, group_id: str) -> int:
        # Event IDs never move backwards, even after a group's retained events expire.
        # Clients use this high-water mark to distinguish retention from state rollback.
        return self._next_event_id - 1

    def current_cursor(self, group_id: str) -> int:
        with self._condition:
            return self._current_cursor_locked(group_id)

    def _cursor_token_locked(self, cursor: int) -> str | None:
        if cursor <= 0:
            return None
        if cursor == self._next_event_id - 1:
            return self._head_event_uid
        return next(
            (
                event["eventUid"]
                for group_events in self._events.values()
                for event in group_events
                if event["id"] == cursor
            ),
            None,
        )

    def cursor_token(self, cursor: int) -> str | None:
        with self._condition:
            return self._cursor_token_locked(cursor)

    def cursor_state(self, cursor: int | None = None) -> tuple[int, str | None, str | None]:
        with self._condition:
            head_cursor = self._current_cursor_locked("")
            target_cursor = head_cursor if cursor is None else cursor
            return (
                head_cursor,
                self._head_event_uid,
                self._cursor_token_locked(target_cursor),
            )

    def _cursor_is_valid_locked(self, cursor: int, supplied_token: str | None) -> bool:
        if supplied_token is None:
            return True
        expected_token = self._cursor_token_locked(cursor)
        return expected_token is not None and hmac.compare_digest(
            supplied_token, expected_token
        )

    def cursor_is_valid(self, cursor: int, supplied_token: str | None) -> bool:
        with self._condition:
            return self._cursor_is_valid_locked(cursor, supplied_token)

    @staticmethod
    def _public_event(event: dict[str, Any]) -> dict[str, Any]:
        return {key: value for key, value in event.items() if not key.startswith("_")}

    def append_event(
        self,
        *,
        group_id: str,
        event_type: str,
        sender_id: str,
        sender_name: str,
        message: str,
        excluded_client_id: str | None = None,
        monitor_id: str | None = None,
        monitor_name: str | None = None,
    ) -> tuple[dict[str, Any], int, int]:
        with self._condition:
            previous_events = {
                existing_group: list(group_events)
                for existing_group, group_events in self._events.items()
            }
            previous_next_event_id = self._next_event_id
            previous_head_event_uid = self._head_event_uid
            try:
                timestamp = now_ms()
                self._cleanup_events_locked(timestamp)
                event = {
                    "id": self._next_event_id,
                    "eventUid": uuid.uuid4().hex,
                    "groupId": group_id,
                    "type": event_type,
                    "senderId": sender_id,
                    "senderName": sender_name,
                    "message": message,
                    "createdAt": timestamp,
                    "monitorId": monitor_id,
                    "monitorName": monitor_name,
                    "_excludedClientId": excluded_client_id,
                }
                self._next_event_id += 1
                self._head_event_uid = event["eventUid"]
                self._events.setdefault(group_id, []).append(event)
                if len(self._events[group_id]) > self.max_events_per_group:
                    self._events[group_id] = self._events[group_id][-self.max_events_per_group :]
                self._persist_locked()
            except Exception:
                self._events = previous_events
                self._next_event_id = previous_next_event_id
                self._head_event_uid = previous_head_event_uid
                raise

            total, online = self._recipient_counts_locked(group_id, excluded_client_id, timestamp)
            self._condition.notify_all()
            return self._public_event(event), total, online

    def _recipient_counts_locked(
        self, group_id: str, excluded_client_id: str | None, current_time: int
    ) -> tuple[int, int]:
        records = [
            record
            for (record_group, client_id), record in self._clients.items()
            if record_group == group_id and client_id != excluded_client_id
        ]
        online = sum(
            1 for record in records if current_time - record["lastSeenAt"] <= self.heartbeat_timeout_ms
        )
        return len(records), online

    def _collect_locked(
        self, group_id: str, client_id: str, after: int, limit: int
    ) -> tuple[list[dict[str, Any]], int]:
        result: list[dict[str, Any]] = []
        cursor = after
        for event in self._events.get(group_id, []):
            if event["id"] <= after:
                continue
            cursor = event["id"]
            if event.get("_excludedClientId") != client_id:
                result.append(self._public_event(event))
                if len(result) >= limit:
                    break
        return result, cursor

    def _touch_poll_client_locked(self, group_id: str, client_id: str) -> None:
        existing = self._clients.get((group_id, client_id))
        client_name = existing["name"] if existing else client_id
        self._touch_client_locked(group_id, client_id, client_name)

    def _poll_locked(
        self,
        group_id: str,
        client_id: str,
        after: int,
        deadline: float,
        limit: int,
    ) -> tuple[list[dict[str, Any]], int]:
        while True:
            if self._cleanup_events_locked(now_ms()):
                self._persist_locked()
            events, cursor = self._collect_locked(group_id, client_id, after, limit)
            if events or cursor > after:
                return events, cursor
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return [], cursor
            self._condition.wait(remaining)
            self._touch_poll_client_locked(group_id, client_id)

    def poll(
        self,
        group_id: str,
        client_id: str,
        after: int,
        wait_seconds: float,
        limit: int = 100,
    ) -> tuple[list[dict[str, Any]], int]:
        deadline = time.monotonic() + wait_seconds
        with self._condition:
            self._touch_poll_client_locked(group_id, client_id)
            return self._poll_locked(group_id, client_id, after, deadline, limit)

    def poll_with_cursor_token(
        self,
        group_id: str,
        client_id: str,
        after: int,
        after_token: str | None,
        wait_seconds: float,
        limit: int = 100,
    ) -> tuple[list[dict[str, Any]], int, str | None, bool, int, str | None]:
        deadline = time.monotonic() + wait_seconds
        with self._condition:
            self._touch_poll_client_locked(group_id, client_id)
            head_cursor = self._current_cursor_locked(group_id)
            if not self._cursor_is_valid_locked(after, after_token):
                return [], 0, None, False, head_cursor, self._head_event_uid
            events, cursor = self._poll_locked(
                group_id, client_id, after, deadline, limit
            )
            return (
                events,
                cursor,
                self._cursor_token_locked(cursor),
                True,
                self._current_cursor_locked(group_id),
                self._head_event_uid,
            )

    def client_counts(self, group_id: str | None = None) -> dict[str, int]:
        with self._condition:
            timestamp = now_ms()
            records = [
                record
                for (record_group, _), record in self._clients.items()
                if group_id is None or record_group == group_id
            ]
            return {
                "online": sum(
                    1
                    for record in records
                    if timestamp - record["lastSeenAt"] <= self.heartbeat_timeout_ms
                ),
                "total": len(records),
            }


@dataclass(frozen=True)
class MonitorDefinition:
    id: str
    group_id: str
    name: str
    url: str
    method: str
    headers: dict[str, str]
    body: Any
    json_path: str
    operator: str
    threshold: float
    multiplier: float
    offset: float
    poll_interval_seconds: int
    timeout_seconds: int
    alert_repeat_seconds: int
    error_alert_after: int
    enabled: bool
    provider: str = "http"
    gateway_access_key: str = ""
    gateway_secret_key_env: str = "GATEWAY_SK"


@dataclass
class MonitorStatus:
    definition: MonitorDefinition
    last_check_at: int = 0
    last_success_at: int = 0
    last_http_code: int | None = None
    last_raw_value: float | None = None
    last_value: float | None = None
    last_error: str = ""
    breached: bool = False
    consecutive_errors: int = 0
    alert_breached: bool = False
    alert_consecutive_errors: int = 0
    last_alert_at: int = 0
    next_check_at: int = field(default_factory=now_ms)


class MonitorFetchError(Exception):
    def __init__(self, message: str, http_code: int | None = None, retry_after: int = 0):
        super().__init__(message)
        self.http_code = http_code
        self.retry_after = retry_after


def read_json_path(document: Any, path: str) -> Any:
    """Read dot, array index and simple array-filter JSON paths."""
    path = path.strip()
    if not path:
        raise ValueError("jsonPath is empty")
    position = 1 if path.startswith("$") else 0
    value = document
    while position < len(path):
        if path[position] == ".":
            position += 1
            continue
        if path[position] == "[":
            closing = path.find("]", position + 1)
            if closing < 0:
                raise ValueError("jsonPath has an unclosed '['")
            selector = path[position + 1 : closing].strip()
            if selector.isdigit():
                if not isinstance(value, list):
                    raise ValueError(f"expected an array before [{selector}]")
                index = int(selector)
                if index >= len(value):
                    raise ValueError(f"array index [{selector}] is out of range")
                value = value[index]
            elif "=" in selector:
                field_name, expected = selector.split("=", 1)
                field_name = field_name.strip()
                expected = expected.strip().strip("\"'")
                if not isinstance(value, list):
                    raise ValueError(f"expected an array before [{selector}]")
                match = next(
                    (
                        item
                        for item in value
                        if isinstance(item, dict)
                        and field_name in item
                        and str(item[field_name]) == expected
                    ),
                    None,
                )
                if match is None:
                    raise ValueError(f"no array item matches [{selector}]")
                value = match
            elif len(selector) >= 2 and selector[0] == selector[-1] and selector[0] in "\"'":
                key = selector[1:-1]
                if not isinstance(value, dict) or key not in value:
                    raise ValueError(f"JSON field '{key}' was not found")
                value = value[key]
            else:
                raise ValueError(f"unsupported JSON path selector [{selector}]")
            position = closing + 1
            continue

        end = position
        while end < len(path) and path[end] not in ".[":
            end += 1
        key = path[position:end]
        if not key:
            raise ValueError("jsonPath contains an empty field name")
        if not isinstance(value, dict) or key not in value:
            raise ValueError(f"JSON field '{key}' was not found")
        value = value[key]
        position = end
    return value


def compare_value(value: float | Decimal, operator: str, threshold: float) -> bool:
    if isinstance(value, Decimal):
        # GateWay money uses exact decimal comparisons, including equality.
        threshold = Decimal(str(threshold))
    if operator == "<":
        return value < threshold
    if operator == "<=":
        return value <= threshold
    if operator == ">":
        return value > threshold
    if operator == ">=":
        return value >= threshold
    if operator in ("=", "=="):
        if isinstance(value, Decimal):
            return value == threshold
        return math.isclose(value, threshold, rel_tol=0, abs_tol=0.000001)
    raise ValueError(f"unsupported operator: {operator}")


def parse_runtime_config(document: Any) -> tuple[dict[str, str], list[MonitorDefinition]]:
    if not isinstance(document, dict):
        raise ValueError("configuration root must be a JSON object")
    groups_document = document.get("groups", [])
    monitors_document = document.get("monitors", [])
    if not isinstance(groups_document, list):
        raise ValueError("groups must be an array")
    if not isinstance(monitors_document, list):
        raise ValueError("monitors must be an array")

    group_tokens: dict[str, str] = {}
    for index, item in enumerate(groups_document):
        if not isinstance(item, dict):
            raise ValueError(f"groups[{index}] must be an object")
        group_id = require_identifier(item.get("id"), f"groups[{index}].id")
        token = require_text(item.get("sharedToken"), f"groups[{index}].sharedToken", 512)
        if group_id in group_tokens:
            raise ValueError(f"duplicate group id: {group_id}")
        group_tokens[group_id] = token

    definitions: list[MonitorDefinition] = []
    keys: set[tuple[str, str]] = set()
    for index, item in enumerate(monitors_document):
        prefix = f"monitors[{index}]"
        if not isinstance(item, dict):
            raise ValueError(f"{prefix} must be an object")
        monitor_id = require_identifier(item.get("id"), f"{prefix}.id")
        group_id = require_identifier(item.get("groupId"), f"{prefix}.groupId")
        key = (group_id, monitor_id)
        if key in keys:
            raise ValueError(f"duplicate monitor id '{monitor_id}' in group '{group_id}'")
        keys.add(key)
        name = require_text(item.get("name"), f"{prefix}.name", 128)
        url = require_text(item.get("url"), f"{prefix}.url", 2048)
        parsed_url = urllib.parse.urlsplit(url)
        if parsed_url.scheme not in ("http", "https") or not parsed_url.hostname:
            raise ValueError(f"{prefix}.url must be an http:// or https:// URL")
        method = str(item.get("method", "GET")).upper()
        if method not in ("GET", "POST"):
            raise ValueError(f"{prefix}.method must be GET or POST")
        headers_document = parse_headers_document(item, prefix)
        json_path = require_text(item.get("jsonPath"), f"{prefix}.jsonPath", 512)
        operator = str(item.get("operator", "<"))
        if operator not in ("<", "<=", ">", ">=", "=", "=="):
            raise ValueError(f"{prefix}.operator is unsupported")
        poll_interval = int(finite_number(item.get("pollIntervalSeconds", 60), f"{prefix}.pollIntervalSeconds"))
        timeout = int(finite_number(item.get("timeoutSeconds", 10), f"{prefix}.timeoutSeconds"))
        repeat_value = item.get("repeatAlertSeconds", item.get("alertRepeatSeconds", 300))
        error_value = item.get("errorThreshold", item.get("errorAlertAfter", 3))
        repeat = int(finite_number(repeat_value, f"{prefix}.repeatAlertSeconds"))
        error_after = int(finite_number(error_value, f"{prefix}.errorThreshold"))
        if poll_interval < 5:
            raise ValueError(f"{prefix}.pollIntervalSeconds must be at least 5")
        if not 1 <= timeout <= 60:
            raise ValueError(f"{prefix}.timeoutSeconds must be between 1 and 60")
        if repeat != 0 and repeat < 15:
            raise ValueError(f"{prefix}.repeatAlertSeconds must be 0 or at least 15")
        if not 1 <= error_after <= 100:
            raise ValueError(f"{prefix}.errorThreshold must be between 1 and 100")
        enabled = item.get("enabled", True)
        if not isinstance(enabled, bool):
            raise ValueError(f"{prefix}.enabled must be true or false")
        provider = item.get("provider", "http")
        if provider not in ("http", "gateway"):
            raise ValueError(f"{prefix}.provider must be http or gateway")
        gateway_access_key = item.get("gatewayAccessKey", "")
        gateway_secret_key_env = item.get("gatewaySecretKeyEnv", "GATEWAY_SK")
        if provider == "gateway":
            gateway_client.validate_url(url)
            if method != "POST":
                raise ValueError(f"{prefix}.method must be POST for GateWay")
            if not isinstance(gateway_access_key, str) or not gateway_client.ACCESS_KEY_RE.fullmatch(gateway_access_key):
                raise ValueError(f"{prefix}.gatewayAccessKey must be gwak_ followed by 24 lowercase hex characters")
            if not isinstance(gateway_secret_key_env, str) or not gateway_client.ENV_NAME_RE.fullmatch(gateway_secret_key_env):
                raise ValueError(f"{prefix}.gatewaySecretKeyEnv must be an environment variable name")
            if headers_document:
                raise ValueError(f"{prefix}.headers must be empty for GateWay; authentication is generated by the server")
            gateway_client.business_params(item.get("body"))
            if poll_interval < 30:
                raise ValueError(f"{prefix}.pollIntervalSeconds must be at least 30 for GateWay")
        definitions.append(
            MonitorDefinition(
                id=monitor_id,
                group_id=group_id,
                name=name,
                url=url,
                method=method,
                headers=dict(headers_document),
                body=item.get("body"),
                json_path=json_path,
                operator=operator,
                threshold=finite_number(item.get("threshold"), f"{prefix}.threshold"),
                multiplier=finite_number(item.get("multiplier", 1), f"{prefix}.multiplier"),
                offset=finite_number(item.get("offset", 0), f"{prefix}.offset"),
                poll_interval_seconds=poll_interval,
                timeout_seconds=timeout,
                alert_repeat_seconds=repeat,
                error_alert_after=error_after,
                enabled=enabled,
                provider=provider,
                gateway_access_key=gateway_access_key,
                gateway_secret_key_env=gateway_secret_key_env,
            )
        )
    return group_tokens, definitions


class MonitorManager:
    """Loads monitor configuration, schedules checks and exposes sanitized status."""

    def __init__(
        self,
        relay_state: RelayState,
        config_file: str | Path,
        managed_store_file: str | Path | None = None,
        global_token: str = "",
        reload_interval_seconds: float = 2,
        max_workers: int = 8,
    ) -> None:
        self.relay_state = relay_state
        self.config_file = Path(config_file)
        self.managed_store_file = Path(managed_store_file) if managed_store_file else None
        self.global_token = global_token
        self.reload_interval_seconds = max(0.5, reload_interval_seconds)
        self._lock = threading.RLock()
        self._definitions: dict[tuple[str, str], MonitorDefinition] = {}
        self._statuses: dict[tuple[str, str], MonitorStatus] = {}
        self._group_tokens: dict[str, str] = {}
        self._raw_monitors: dict[tuple[str, str], dict[str, Any]] = {}
        self._in_flight: set[tuple[str, str]] = set()
        self._refresh_lock = threading.Lock()
        self._loaded = False
        self._last_loaded_at = 0
        self._last_error = ""
        self._last_signature: tuple[int | None, int | None] | None = None
        self._scheduler_error = ""
        self._scheduler_last_tick_at = 0
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._thread: threading.Thread | None = None
        self._executor = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="monitor")
        self.reload(force=True)

    def _file_signature(self) -> tuple[int | None, int | None]:
        try:
            config_mtime = self.config_file.stat().st_mtime_ns
        except OSError:
            config_mtime = None
        try:
            store_mtime = (
                self.managed_store_file.stat().st_mtime_ns
                if self.managed_store_file is not None
                else None
            )
        except OSError:
            store_mtime = None
        return config_mtime, store_mtime

    @staticmethod
    def _with_metadata(item: dict[str, Any], timestamp: int) -> dict[str, Any]:
        if not isinstance(item, dict):
            raise ValueError("every monitor must be an object")
        result = dict(item)
        revision = result.get("revision", 1)
        updated_at = result.get("updatedAt", timestamp)
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            revision = 1
        if not isinstance(updated_at, int) or isinstance(updated_at, bool) or updated_at < 1:
            updated_at = timestamp
        result["revision"] = revision
        result["updatedAt"] = updated_at
        return result

    def _apply_loaded_config_locked(
        self,
        group_tokens: dict[str, str],
        raw_monitors: list[dict[str, Any]],
        definitions: list[MonitorDefinition],
        timestamp: int,
    ) -> None:
        new_definitions = {(item.group_id, item.id): item for item in definitions}
        new_statuses: dict[tuple[str, str], MonitorStatus] = {}
        for key, definition in new_definitions.items():
            previous = self._statuses.get(key)
            if previous is not None and previous.definition == definition:
                new_statuses[key] = previous
            else:
                new_statuses[key] = MonitorStatus(definition=definition, next_check_at=timestamp)
        self._definitions = new_definitions
        self._statuses = new_statuses
        self._group_tokens = group_tokens
        self._raw_monitors = {
            (item["groupId"], item["id"]): dict(item) for item in raw_monitors
        }
        self._loaded = True
        self._last_loaded_at = timestamp
        self._last_error = ""

    def reload(self, force: bool = False) -> bool:
        # Serialize file reads with CRUD writes so an older snapshot cannot win a race.
        with self._lock:
            return self._reload_locked(force)

    def _reload_locked(self, force: bool = False) -> bool:
        signature = self._file_signature()
        try:
            if not force and signature == self._last_signature:
                return False
            try:
                stat = self.config_file.stat()
            except FileNotFoundError:
                if not self.global_token:
                    raise
                document = {"groups": [], "monitors": []}
            else:
                if stat.st_size > MAX_RESPONSE_BYTES:
                    raise ValueError("configuration file is too large")
                document = json.loads(self.config_file.read_text(encoding="utf-8"))
            if not isinstance(document, dict):
                raise ValueError("configuration root must be a JSON object")
            timestamp = now_ms()
            base_monitors = document.get("monitors", [])
            raw_monitors: list[dict[str, Any]]
            if self.managed_store_file is not None:
                if self.managed_store_file.exists():
                    if self.managed_store_file.stat().st_size > MAX_RESPONSE_BYTES:
                        raise ValueError("managed monitor store is too large")
                    managed_document = json.loads(
                        self.managed_store_file.read_text(encoding="utf-8")
                    )
                    if not isinstance(managed_document, dict) or not isinstance(
                        managed_document.get("monitors"), list
                    ):
                        raise ValueError("managed monitor store must contain a monitors array")
                    raw_monitors = managed_document["monitors"]
                else:
                    if not isinstance(base_monitors, list):
                        raise ValueError("monitors must be an array")
                    raw_monitors = [self._with_metadata(item, timestamp) for item in base_monitors]
                    atomic_write_json(self.managed_store_file, {"monitors": raw_monitors})
            else:
                if not isinstance(base_monitors, list):
                    raise ValueError("monitors must be an array")
                raw_monitors = [self._with_metadata(item, timestamp) for item in base_monitors]

            raw_monitors = [self._with_metadata(item, timestamp) for item in raw_monitors]
            runtime_document = dict(document)
            runtime_document["monitors"] = raw_monitors
            group_tokens, definitions = parse_runtime_config(runtime_document)
            if not self.global_token and not group_tokens:
                raise ValueError(
                    "no shared token configured; set RELAY_TOKEN or add "
                    "groups[].sharedToken to config.json"
                )
            if not self.global_token:
                for definition in definitions:
                    if definition.group_id not in group_tokens:
                        raise ValueError(
                            f"groupId '{definition.group_id}' is not configured in groups"
                        )
            with self._lock:
                self._apply_loaded_config_locked(
                    group_tokens, raw_monitors, definitions, timestamp
                )
                self._last_signature = self._file_signature()
            self._wake.set()
            print(
                f"loaded {len(definitions)} monitor(s) and {len(group_tokens)} group(s) "
                f"from {self.config_file}",
                file=sys.stderr,
            )
            return True
        except (OSError, ValueError, json.JSONDecodeError) as error:
            with self._lock:
                self._last_error = str(error)
                self._last_signature = signature
            if force or self._loaded:
                print(f"warning: could not load monitor config: {error}", file=sys.stderr)
            return False

    def _group_documents_locked(self) -> list[dict[str, str]]:
        return [
            {"id": group_id, "sharedToken": token}
            for group_id, token in self._group_tokens.items()
        ]

    def _commit_raw_monitors_locked(self, raw_monitors: list[dict[str, Any]]) -> None:
        if self.managed_store_file is None:
            raise ApiError(
                HTTPStatus.SERVICE_UNAVAILABLE,
                "managed_store_disabled",
                "managed monitor storage is not configured",
            )
        _, definitions = parse_runtime_config(
            {"groups": self._group_documents_locked(), "monitors": raw_monitors}
        )
        if not self.global_token:
            for definition in definitions:
                if definition.group_id not in self._group_tokens:
                    raise ValueError(
                        f"groupId '{definition.group_id}' is not configured in groups"
                    )
        atomic_write_json(self.managed_store_file, {"monitors": raw_monitors})
        timestamp = now_ms()
        self._apply_loaded_config_locked(
            dict(self._group_tokens), raw_monitors, definitions, timestamp
        )
        self._last_signature = self._file_signature()
        self._wake.set()

    @staticmethod
    def _headers_from_raw(item: dict[str, Any]) -> dict[str, str]:
        return parse_headers_document(item, "monitor")

    def _normalize_admin_document(
        self,
        candidate: dict[str, Any],
        previous: dict[str, Any] | None,
        monitor_id: str,
        group_id: str,
    ) -> dict[str, Any]:
        allowed = {
            "id",
            "groupId",
            "name",
            "url",
            "method",
            "headers",
            "headersJson",
            "body",
            "jsonPath",
            "operator",
            "threshold",
            "multiplier",
            "offset",
            "pollIntervalSeconds",
            "timeoutSeconds",
            "errorThreshold",
            "errorAlertAfter",
            "repeatAlertSeconds",
            "alertRepeatSeconds",
            "enabled",
            "provider",
            "gatewayAccessKey",
            "gatewaySecretKeyEnv",
            "revision",
            "updatedAt",
        }
        unknown = set(candidate) - allowed
        if unknown:
            raise ValueError(f"unsupported monitor field(s): {', '.join(sorted(unknown))}")
        result = dict(previous) if previous is not None else {
            "method": "GET",
            "headers": {},
            "body": None,
            "operator": "<",
            "multiplier": 1,
            "offset": 0,
            "pollIntervalSeconds": 60,
            "timeoutSeconds": 10,
            "errorThreshold": 3,
            "repeatAlertSeconds": 300,
            "enabled": True,
        }
        result["id"] = monitor_id
        result["groupId"] = group_id
        for field_name in (
            "name",
            "method",
            "jsonPath",
            "operator",
            "threshold",
            "multiplier",
            "offset",
            "pollIntervalSeconds",
            "timeoutSeconds",
            "errorThreshold",
            "repeatAlertSeconds",
            "enabled",
            "provider",
            "gatewayAccessKey",
            "gatewaySecretKeyEnv",
        ):
            if field_name in candidate:
                result[field_name] = candidate[field_name]
        if "errorAlertAfter" in candidate:
            result["errorThreshold"] = candidate["errorAlertAfter"]
        if "alertRepeatSeconds" in candidate:
            result["repeatAlertSeconds"] = candidate["alertRepeatSeconds"]

        if "url" in candidate:
            url = candidate["url"]
            if not isinstance(url, str):
                raise ValueError("url must be a string")
            if previous is not None:
                url = restore_masked_url(url, str(previous.get("url", "")))
            elif KEEP_PLACEHOLDER in url:
                raise ValueError("url placeholder cannot be used when creating a monitor")
            result["url"] = url

        if "headersJson" in candidate or "headers" in candidate:
            incoming_headers = parse_headers_document(candidate, "monitor")
            previous_headers = self._headers_from_raw(previous) if previous is not None else {}
            restored_headers: dict[str, str] = {}
            for name, value in incoming_headers.items():
                if value == KEEP_PLACEHOLDER:
                    if name not in previous_headers:
                        raise ValueError(
                            f"header placeholder has no previous value for '{name}'"
                        )
                    value = previous_headers[name]
                restored_headers[name] = value
            result["headers"] = restored_headers
            result.pop("headersJson", None)

        if "body" in candidate:
            incoming_body = candidate["body"]
            if incoming_body == KEEP_PLACEHOLDER:
                if previous is None or "body" not in previous:
                    raise ValueError("body placeholder cannot be used when creating a monitor")
            else:
                result["body"] = incoming_body

        result.pop("errorAlertAfter", None)
        result.pop("alertRepeatSeconds", None)
        result["revision"] = (int(previous.get("revision", 0)) + 1) if previous else 1
        result["updatedAt"] = now_ms()
        return result

    @staticmethod
    def _admin_monitor_config(item: dict[str, Any]) -> dict[str, Any]:
        headers = MonitorManager._headers_from_raw(item)
        body_configured = item.get("body") is not None
        return {
            "id": item["id"],
            "groupId": item["groupId"],
            "name": item.get("name", ""),
            "url": str(item.get("url", "")),
            "urlMasked": False,
            "method": str(item.get("method", "GET")).upper(),
            "provider": item.get("provider", "http"),
            "gatewayAccessKey": item.get("gatewayAccessKey", ""),
            "gatewaySecretKeyEnv": item.get("gatewaySecretKeyEnv", "GATEWAY_SK"),
            "headersJson": json.dumps(
                headers,
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            "headersConfigured": bool(headers),
            "body": deepcopy(item.get("body")) if body_configured else None,
            "bodyConfigured": body_configured,
            "jsonPath": item.get("jsonPath", ""),
            "operator": item.get("operator", "<"),
            "threshold": item.get("threshold"),
            "multiplier": item.get("multiplier", 1),
            "offset": item.get("offset", 0),
            "pollIntervalSeconds": item.get("pollIntervalSeconds", 60),
            "timeoutSeconds": item.get("timeoutSeconds", 10),
            "errorThreshold": item.get("errorThreshold", item.get("errorAlertAfter", 3)),
            "repeatAlertSeconds": item.get(
                "repeatAlertSeconds", item.get("alertRepeatSeconds", 300)
            ),
            "enabled": item.get("enabled", True),
            "revision": item.get("revision", 1),
            "updatedAt": item.get("updatedAt", 0),
        }

    def list_monitor_configs(self, group_id: str) -> list[dict[str, Any]]:
        with self._lock:
            return [
                self._admin_monitor_config(item)
                for (item_group, _), item in self._raw_monitors.items()
                if item_group == group_id
            ]

    @staticmethod
    def _monitor_id_list(value: Any, field_name: str) -> list[str]:
        if not isinstance(value, list):
            raise ValueError(f"{field_name} must be an array")
        monitor_ids = [
            require_identifier(monitor_id, f"{field_name}[{index}]")
            for index, monitor_id in enumerate(value)
        ]
        if len(set(monitor_ids)) != len(monitor_ids):
            raise ValueError(f"{field_name} must not contain duplicate monitor ids")
        return monitor_ids

    def _group_monitor_ids_locked(self, group_id: str) -> list[str]:
        return [
            monitor_id
            for item_group, monitor_id in self._raw_monitors
            if item_group == group_id
        ]

    def _unique_copy_name_locked(self, group_id: str, source_name: str) -> str:
        existing_names = {
            str(item.get("name", ""))
            for (item_group, _), item in self._raw_monitors.items()
            if item_group == group_id
        }
        copy_number = 1
        while True:
            suffix = "（副本）" if copy_number == 1 else f"（副本{copy_number}）"
            candidate = f"{source_name[:128 - len(suffix)]}{suffix}"
            if candidate not in existing_names:
                return candidate
            copy_number += 1

    def copy_monitor_config(self, group_id: str, monitor_id: str) -> dict[str, Any]:
        key = (group_id, monitor_id)
        with self._lock:
            source = self._raw_monitors.get(key)
            if source is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "monitor not found")

            existing_ids = {existing_id for _, existing_id in self._raw_monitors}
            copied_id = uuid.uuid4().hex
            while copied_id in existing_ids:
                copied_id = uuid.uuid4().hex
            copied = deepcopy(source)
            copied["id"] = copied_id
            copied["groupId"] = group_id
            copied["name"] = self._unique_copy_name_locked(
                group_id, str(source.get("name", ""))
            )
            copied["enabled"] = False
            copied["revision"] = 1
            copied["updatedAt"] = now_ms()

            raw_monitors: list[dict[str, Any]] = []
            for raw_key, value in self._raw_monitors.items():
                raw_monitors.append(dict(value))
                if raw_key == key:
                    raw_monitors.append(copied)
            self._commit_raw_monitors_locked(raw_monitors)
            return self._admin_monitor_config(copied)

    def reorder_monitor_configs(
        self,
        group_id: str,
        expected_monitor_ids: Any,
        monitor_ids: Any,
    ) -> list[dict[str, Any]]:
        with self._lock:
            expected = self._monitor_id_list(
                expected_monitor_ids, "expectedMonitorIds"
            )
            requested = self._monitor_id_list(monitor_ids, "monitorIds")
            current = self._group_monitor_ids_locked(group_id)
            if expected != current or set(requested) != set(current):
                raise ApiError(
                    HTTPStatus.CONFLICT,
                    "order_conflict",
                    "monitor order changed; refresh and retry",
                )

            requested_iterator = iter(requested)
            raw_monitors: list[dict[str, Any]] = []
            for (item_group, _), value in self._raw_monitors.items():
                if item_group == group_id:
                    requested_id = next(requested_iterator)
                    value = self._raw_monitors[(group_id, requested_id)]
                raw_monitors.append(dict(value))
            self._commit_raw_monitors_locked(raw_monitors)
            return [
                self._admin_monitor_config(item)
                for (item_group, _), item in self._raw_monitors.items()
                if item_group == group_id
            ]

    def create_monitor_config(self, candidate: dict[str, Any]) -> dict[str, Any]:
        group_id = require_identifier(candidate.get("groupId"), "groupId")
        raw_id = candidate.get("id")
        monitor_id = (
            require_identifier(raw_id, "id") if raw_id is not None else uuid.uuid4().hex
        )
        key = (group_id, monitor_id)
        with self._lock:
            if key in self._raw_monitors:
                raise ApiError(HTTPStatus.CONFLICT, "already_exists", "monitor id already exists")
            item = self._normalize_admin_document(candidate, None, monitor_id, group_id)
            raw_monitors = [dict(value) for value in self._raw_monitors.values()] + [item]
            self._commit_raw_monitors_locked(raw_monitors)
            return self._admin_monitor_config(item)

    def update_monitor_config(
        self, group_id: str, monitor_id: str, candidate: dict[str, Any]
    ) -> dict[str, Any]:
        key = (group_id, monitor_id)
        with self._lock:
            previous = self._raw_monitors.get(key)
            if previous is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "monitor not found")
            supplied_revision = candidate.get("revision")
            if supplied_revision is not None and supplied_revision != previous.get("revision", 1):
                raise ApiError(
                    HTTPStatus.CONFLICT,
                    "revision_conflict",
                    "monitor was changed by another client; refresh and retry",
                )
            if "id" in candidate and candidate["id"] != monitor_id:
                raise ValueError("id in request body must match URL")
            item = self._normalize_admin_document(
                candidate, previous, monitor_id, group_id
            )
            raw_monitors = [
                item if raw_key == key else dict(value)
                for raw_key, value in self._raw_monitors.items()
            ]
            self._commit_raw_monitors_locked(raw_monitors)
            return self._admin_monitor_config(item)

    def delete_monitor_config(self, group_id: str, monitor_id: str) -> None:
        key = (group_id, monitor_id)
        with self._lock:
            if key not in self._raw_monitors:
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "monitor not found")
            raw_monitors = [
                dict(value)
                for raw_key, value in self._raw_monitors.items()
                if raw_key != key
            ]
            self._commit_raw_monitors_locked(raw_monitors)

    @staticmethod
    def _monitor_test_error(
        code: str, message: str, http_code: int | None = None
    ) -> dict[str, Any]:
        return {
            "ok": False,
            "serverTime": now_ms(),
            "error": {
                "code": code,
                "message": message,
                "httpCode": http_code,
            },
        }

    def _release_in_flight(self, key: tuple[str, str]) -> None:
        with self._lock:
            self._in_flight.discard(key)
        self._wake.set()

    def _test_monitor_definition(self, definition: MonitorDefinition) -> dict[str, Any]:
        key = (definition.group_id, definition.id)
        try:
            http_code, raw_value, value = self._fetch_value(definition)
            timestamp = now_ms()
            breached = compare_value(value, definition.operator, definition.threshold)
            with self._lock:
                status = self._statuses.get(key)
                if status is None or status.definition != definition:
                    return self._monitor_test_error(
                        "monitor_changed",
                        "monitor was changed during the test; result was not published",
                    )
                status.last_check_at = timestamp
                status.last_success_at = timestamp
                status.last_http_code = http_code
                status.last_raw_value = float(raw_value)
                status.last_value = float(value)
                status.last_error = ""
                status.breached = breached
                status.consecutive_errors = 0
            return {
                "ok": True,
                "serverTime": timestamp,
                "result": {
                    "httpCode": http_code,
                    "rawValue": float(raw_value),
                    "value": float(value),
                    "breached": breached,
                },
            }
        except Exception as error:
            with self._lock:
                status = self._statuses.get(key)
                if status is None or status.definition != definition:
                    return self._monitor_test_error(
                        "monitor_changed",
                        "monitor was changed during the test; result was not published",
                    )
            fetch_error = error if isinstance(error, MonitorFetchError) else MonitorFetchError(str(error))
            return self._monitor_test_error(
                "monitor_test_failed",
                str(fetch_error)[:1000],
                fetch_error.http_code,
            )

    def test_monitor_config(self, group_id: str, monitor_id: str) -> dict[str, Any]:
        key = (group_id, monitor_id)
        with self._lock:
            definition = self._definitions.get(key)
            if definition is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "monitor not found")
            if key in self._in_flight:
                raise ApiError(
                    HTTPStatus.CONFLICT,
                    "monitor_busy",
                    "monitor check already in progress",
                )
            self._in_flight.add(key)
        try:
            return self._test_monitor_definition(definition)
        finally:
            self._release_in_flight(key)

    def refresh_monitor_configs(self, group_id: str) -> dict[str, Any]:
        if not self._refresh_lock.acquire(blocking=False):
            raise ApiError(
                HTTPStatus.CONFLICT,
                "refresh_in_progress",
                "monitor refresh already in progress",
            )
        try:
            with self._lock:
                enabled_monitors = [
                    definition
                    for (definition_group, _), definition in self._definitions.items()
                    if definition_group == group_id and definition.enabled
                ]

            jobs: list[
                tuple[MonitorDefinition, Future[dict[str, Any]] | None, dict[str, Any] | None]
            ] = []
            for definition in enabled_monitors:
                key = (definition.group_id, definition.id)
                with self._lock:
                    if key in self._in_flight:
                        jobs.append(
                            (
                                definition,
                                None,
                                self._monitor_test_error(
                                    "monitor_busy",
                                    "monitor check already in progress",
                                ),
                            )
                        )
                        continue
                    self._in_flight.add(key)
                try:
                    future = self._executor.submit(
                        self._test_monitor_definition, definition
                    )
                except RuntimeError:
                    self._release_in_flight(key)
                    jobs.append(
                        (
                            definition,
                            None,
                            self._monitor_test_error(
                                "monitor_refresh_failed",
                                "monitor executor is unavailable",
                            ),
                        )
                    )
                else:
                    future.add_done_callback(
                        lambda _completed, monitor_key=key: self._release_in_flight(
                            monitor_key
                        )
                    )
                    jobs.append((definition, future, None))

            results: list[dict[str, Any]] = []
            for definition, future, immediate_outcome in jobs:
                if immediate_outcome is not None:
                    outcome = immediate_outcome
                elif future is not None:
                    try:
                        outcome = future.result()
                    except Exception:
                        outcome = self._monitor_test_error(
                            "monitor_refresh_failed",
                            "monitor became unavailable during refresh",
                        )
                else:  # Defensive fallback for an invalid internal job record.
                    outcome = self._monitor_test_error(
                        "monitor_refresh_failed", "monitor refresh failed"
                    )
                item = {
                    "monitorId": definition.id,
                    "monitorName": definition.name,
                    "ok": bool(outcome.get("ok")),
                }
                if item["ok"]:
                    item["result"] = deepcopy(outcome.get("result", {}))
                else:
                    item["error"] = deepcopy(
                        outcome.get(
                            "error",
                            {
                                "code": "monitor_refresh_failed",
                                "message": "monitor refresh failed",
                                "httpCode": None,
                            },
                        )
                    )
                results.append(item)

            succeeded = sum(1 for item in results if item["ok"])
            return {
                "ok": succeeded == len(results),
                "serverTime": now_ms(),
                "total": len(results),
                "succeeded": succeeded,
                "failed": len(results) - succeeded,
                "results": results,
            }
        finally:
            self._refresh_lock.release()

    def has_authentication(self) -> bool:
        with self._lock:
            return bool(self.global_token or self._group_tokens)

    def token_is_group_token(self, supplied_token: str) -> bool:
        if not supplied_token:
            return False
        with self._lock:
            candidates = [
                token for token in (self.global_token, *self._group_tokens.values()) if token
            ]
        return any(hmac.compare_digest(supplied_token, candidate) for candidate in candidates)

    def authorize(self, group_id: str, supplied_token: str) -> bool:
        with self._lock:
            group_token = self._group_tokens.get(group_id)
            candidates = [token for token in (group_token, self.global_token) if token]
        return any(hmac.compare_digest(supplied_token, candidate) for candidate in candidates)

    def single_group_id(self) -> str | None:
        """Return the configured group only when routing is unambiguous."""
        with self._lock:
            if len(self._group_tokens) != 1:
                return None
            return next(iter(self._group_tokens))

    def start(self) -> None:
        if self._thread is not None:
            return
        self._scheduler_error = ""
        self._thread = threading.Thread(target=self._scheduler_loop, name="monitor-scheduler", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
        self._executor.shutdown(wait=True, cancel_futures=True)

    def _scheduler_loop(self) -> None:
        next_reload = time.monotonic()
        while not self._stop.is_set():
            try:
                monotonic_now = time.monotonic()
                if monotonic_now >= next_reload:
                    self.reload(force=False)
                    next_reload = monotonic_now + self.reload_interval_seconds
                timestamp = now_ms()
                due: list[tuple[tuple[str, str], MonitorDefinition]] = []
                with self._lock:
                    self._scheduler_last_tick_at = timestamp
                    for key, status in self._statuses.items():
                        if (
                            status.definition.enabled
                            and key not in self._in_flight
                            and status.next_check_at <= timestamp
                        ):
                            self._in_flight.add(key)
                            status.next_check_at = (
                                timestamp + status.definition.poll_interval_seconds * 1000
                            )
                            due.append((key, status.definition))
                for key, definition in due:
                    try:
                        future = self._executor.submit(self._execute_check, definition)
                    except RuntimeError:
                        self._release_in_flight(key)
                        continue
                    else:
                        future.add_done_callback(
                            lambda completed, monitor_key=key: self._check_finished(
                                monitor_key, completed
                            )
                        )
            except Exception as error:
                with self._lock:
                    self._scheduler_error = (
                        f"monitor scheduler failed ({type(error).__name__})"
                    )
                print(f"warning: monitor scheduler iteration failed: {error}", file=sys.stderr)
            self._wake.wait(timeout=0.5)
            self._wake.clear()

    def _check_finished(self, key: tuple[str, str], future: Future[None]) -> None:
        try:
            error = future.exception()
        except CancelledError:
            error = None
        self._release_in_flight(key)
        if error is not None:
            print(
                f"warning: monitor check {key[0]}/{key[1]} failed: {error}",
                file=sys.stderr,
            )

    def check_monitor(self, group_id: str, monitor_id: str) -> None:
        """Run one check synchronously; used by tests and operational diagnostics."""
        key = (group_id, monitor_id)
        with self._lock:
            definition = self._definitions.get(key)
            if definition is None:
                raise KeyError(f"unknown monitor {group_id}/{monitor_id}")
            if key in self._in_flight:
                raise RuntimeError(f"monitor check already in progress: {group_id}/{monitor_id}")
            self._in_flight.add(key)
        try:
            self._execute_check(definition)
        finally:
            self._release_in_flight(key)

    def _fetch_gateway_value(self, definition: MonitorDefinition) -> tuple[int, Decimal, Decimal]:
        secret_key = os.environ.get(definition.gateway_secret_key_env, "").strip()
        if not secret_key:
            raise MonitorFetchError("GateWay SK environment variable is not configured on the server")
        try:
            http_code, document = gateway_client.call(
                definition.url, definition.gateway_access_key, secret_key,
                definition.body, definition.timeout_seconds,
            )
        except gateway_client.GatewayError as error:
            raise MonitorFetchError(str(error), error.http_code, error.retry_after) from error
        except Exception as error:
            # Never include secret values or raw request/response text in an alert.
            raise MonitorFetchError("GateWay request configuration or credentials are invalid") from error
        try:
            raw = read_json_path(document, definition.json_path)
            if isinstance(raw, bool) or not isinstance(raw, (str, int, float, Decimal)):
                raise ValueError()
            raw_value = Decimal(str(raw))
            if not raw_value.is_finite():
                raise ValueError()
            with localcontext() as context:
                context.prec = 50
                value = raw_value * Decimal(str(definition.multiplier)) + Decimal(str(definition.offset))
            if not math.isfinite(float(raw_value)) or not math.isfinite(float(value)):
                raise ValueError()
        except (ValueError, InvalidOperation, OverflowError) as error:
            raise MonitorFetchError("GateWay JSON field must contain a finite decimal number", http_code) from error
        return http_code, raw_value, value

    def _fetch_value(self, definition: MonitorDefinition) -> tuple[int, float | Decimal, float | Decimal]:
        if definition.provider == "gateway":
            return self._fetch_gateway_value(definition)
        headers = dict(definition.headers)
        body_bytes: bytes | None = None
        if definition.method == "POST":
            if definition.body is None:
                body_bytes = b""
            elif isinstance(definition.body, str):
                body_bytes = definition.body.encode("utf-8")
            else:
                body_bytes = json.dumps(
                    definition.body, ensure_ascii=False, separators=(",", ":")
                ).encode("utf-8")
                if not any(key.lower() == "content-type" for key in headers):
                    headers["Content-Type"] = "application/json"
        try:
            request = urllib.request.Request(
                definition.url,
                data=body_bytes,
                headers=headers,
                method=definition.method,
            )
            with urllib.request.urlopen(request, timeout=definition.timeout_seconds) as response:
                http_code = response.getcode()
                payload = response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            raise MonitorFetchError(f"HTTP {error.code}", error.code) from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            reason = getattr(error, "reason", error)
            raise MonitorFetchError(str(reason)) from error
        except Exception as error:
            # Request-construction errors can contain raw URL/header values.
            raise MonitorFetchError(
                f"request failed ({type(error).__name__})"
            ) from error
        if len(payload) > MAX_RESPONSE_BYTES:
            raise MonitorFetchError("response exceeds 1 MiB", http_code)
        try:
            document = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise MonitorFetchError(f"invalid JSON response: {error}", http_code) from error
        try:
            raw_value = finite_number(read_json_path(document, definition.json_path), "JSON value")
        except ValueError as error:
            raise MonitorFetchError(str(error), http_code) from error
        converted_value = raw_value * definition.multiplier + definition.offset
        if not math.isfinite(converted_value):
            raise MonitorFetchError("converted value is not finite", http_code)
        return http_code, raw_value, converted_value

    def _execute_check(self, definition: MonitorDefinition) -> None:
        timestamp = now_ms()
        event: dict[str, str] | None = None
        rollback: tuple[str, Any, int] | None = None
        try:
            http_code, raw_value, converted_value = self._fetch_value(definition)
            breached = compare_value(converted_value, definition.operator, definition.threshold)
            with self._lock:
                key = (definition.group_id, definition.id)
                status = self._statuses.get(key)
                if status is None or status.definition != definition:
                    return
                was_breached = status.alert_breached
                repeat_due = (
                    definition.alert_repeat_seconds > 0
                    and timestamp - status.last_alert_at >= definition.alert_repeat_seconds * 1000
                )
                status.last_check_at = timestamp
                status.last_success_at = timestamp
                status.last_http_code = http_code
                status.last_raw_value = float(raw_value)
                status.last_value = float(converted_value)
                status.last_error = ""
                status.breached = breached
                status.consecutive_errors = 0
                status.alert_breached = breached
                status.alert_consecutive_errors = 0
                status.next_check_at = timestamp + definition.poll_interval_seconds * 1000
                if breached and (not was_breached or repeat_due):
                    rollback = ("breach", was_breached, status.last_alert_at)
                    status.last_alert_at = timestamp
                    event = {
                        "type": "monitor_breach",
                        "message": (
                            f"{definition.name}: {converted_value:g} "
                            f"{definition.operator} {definition.threshold:g}"
                        ),
                    }
        except Exception as error:  # The scheduler must survive every endpoint failure.
            fetch_error = error if isinstance(error, MonitorFetchError) else MonitorFetchError(str(error))
            with self._lock:
                key = (definition.group_id, definition.id)
                status = self._statuses.get(key)
                if status is None or status.definition != definition:
                    return
                previous_errors = status.alert_consecutive_errors
                status.last_check_at = timestamp
                status.last_http_code = fetch_error.http_code
                status.last_error = str(fetch_error)[:1000]
                status.alert_consecutive_errors += 1
                status.consecutive_errors = status.alert_consecutive_errors
                status.next_check_at = max(
                    timestamp + definition.poll_interval_seconds * 1000,
                    now_ms() + fetch_error.retry_after * 1000,
                )
                crossed_threshold = (
                    previous_errors < definition.error_alert_after
                    <= status.consecutive_errors
                )
                repeat_due = (
                    definition.alert_repeat_seconds > 0
                    and status.alert_consecutive_errors >= definition.error_alert_after
                    and timestamp - status.last_alert_at >= definition.alert_repeat_seconds * 1000
                )
                if crossed_threshold or repeat_due:
                    rollback = ("error", previous_errors, status.last_alert_at)
                    status.last_alert_at = timestamp
                    event = {
                        "type": "monitor_error",
                        "message": f"{definition.name} check failed: {status.last_error}",
                    }

        if event is not None:
            try:
                self.relay_state.append_event(
                    group_id=definition.group_id,
                    event_type=event["type"],
                    sender_id="server",
                    sender_name="CreditInform Server",
                    message=event["message"],
                    monitor_id=definition.id,
                    monitor_name=definition.name,
                )
            except Exception as error:
                with self._lock:
                    key = (definition.group_id, definition.id)
                    status = self._statuses.get(key)
                    if (
                        rollback is not None
                        and status is not None
                        and status.definition == definition
                        and status.last_check_at == timestamp
                    ):
                        kind, previous_value, previous_alert_at = rollback
                        status.last_alert_at = previous_alert_at
                        if kind == "breach":
                            status.alert_breached = bool(previous_value)
                        else:
                            status.alert_consecutive_errors = int(previous_value)
                            status.consecutive_errors = int(previous_value)
                        status.last_error = f"could not persist alert: {error}"[:1000]
                raise

    def config_status(self, probe_storage: bool = False) -> dict[str, Any]:
        with self._lock:
            scheduler_alive = self._thread is None or self._thread.is_alive()
            scheduler_error = self._scheduler_error
            if not scheduler_alive and not scheduler_error:
                scheduler_error = "monitor scheduler is not running"
            loaded = self._loaded
            last_loaded_at = self._last_loaded_at
            config_error = self._last_error
            scheduler_last_tick_at = self._scheduler_last_tick_at
        storage = self.relay_state.storage_status(probe=probe_storage)
        errors = [
            error
            for error in (config_error, scheduler_error, storage["lastError"])
            if error
        ]
        return {
            "loaded": loaded,
            "lastLoadedAt": last_loaded_at,
            "lastError": "; ".join(errors),
            "schedulerAlive": scheduler_alive,
            "schedulerLastTickAt": scheduler_last_tick_at,
            "storage": storage,
        }

    @staticmethod
    def _public_status(status: MonitorStatus) -> dict[str, Any]:
        definition = status.definition
        safe_url, _ = mask_url(definition.url)
        return {
            "id": definition.id,
            "name": definition.name,
            "url": safe_url,
            "method": definition.method,
            "enabled": definition.enabled,
            "operator": definition.operator,
            "threshold": definition.threshold,
            "pollIntervalSeconds": definition.poll_interval_seconds,
            "lastCheckAt": status.last_check_at,
            "lastSuccessAt": status.last_success_at,
            "lastHttpCode": status.last_http_code,
            "lastRawValue": status.last_raw_value,
            "lastValue": status.last_value,
            "lastError": status.last_error,
            "breached": status.breached,
            "consecutiveErrors": status.consecutive_errors,
            "nextCheckAt": status.next_check_at if definition.enabled else 0,
        }

    def statuses_for_group(self, group_id: str) -> list[dict[str, Any]]:
        with self._lock:
            return [
                self._public_status(status)
                for (status_group, _), status in self._statuses.items()
                if status_group == group_id
            ]

    def summary(self, group_id: str | None = None) -> dict[str, int]:
        with self._lock:
            statuses = [
                status
                for (status_group, _), status in self._statuses.items()
                if group_id is None or status_group == group_id
            ]
            return {
                "configured": len(statuses),
                "enabled": sum(1 for status in statuses if status.definition.enabled),
                "breached": sum(1 for status in statuses if status.breached),
                "errors": sum(1 for status in statuses if status.last_error),
            }


class RelayApplication:
    def __init__(
        self,
        relay_state: RelayState,
        monitor_manager: MonitorManager,
        max_poll_seconds: int = 30,
        admin_token: str = "",
    ) -> None:
        self.relay_state = relay_state
        self.monitor_manager = monitor_manager
        self.max_poll_seconds = max_poll_seconds
        self.admin_token = admin_token
        self.started_at = time.monotonic()

    def authorize(self, group_id: str, token: str) -> bool:
        return self.monitor_manager.authorize(group_id, token)

    def authorize_admin(self, token: str) -> bool:
        return bool(
            self.admin_token
            and hmac.compare_digest(token, self.admin_token)
            and not self.monitor_manager.token_is_group_token(token)
        )

    def health(self) -> dict[str, Any]:
        config = self.monitor_manager.config_status(probe_storage=True)
        healthy = config["loaded"] and not config["lastError"]
        return {
            "status": "ok" if healthy else "degraded",
            "service": "credit-inform-relay",
            "version": VERSION,
            "serverInstanceId": self.relay_state.server_instance_id,
            "serverTime": now_ms(),
            "uptimeSeconds": int(time.monotonic() - self.started_at),
            "heartbeatTimeoutSeconds": self.relay_state.heartbeat_timeout_ms // 1000,
            "clients": self.relay_state.client_counts(),
            "monitors": self.monitor_manager.summary(),
            "monitoringStatus": "ok" if healthy else "degraded",
            "config": config,
        }


class RelayHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


@dataclass(frozen=True)
class AdminPage:
    body: bytes
    content_security_policy: str


def load_admin_page(path: Path = ADMIN_PAGE_FILE) -> AdminPage | None:
    """Load the bundled admin page; only its exact inline script and style may run."""
    try:
        raw = path.read_bytes()
    except FileNotFoundError:
        return None
    # Browsers hash inline code after normalizing newlines, so serve and hash the same text.
    text = raw.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
    body = text.encode("utf-8")

    def inline_hashes(tag: str) -> str:
        hashes = [
            "'sha256-"
            + base64.b64encode(hashlib.sha256(match.group(1).encode("utf-8")).digest()).decode(
                "ascii"
            )
            + "'"
            for match in re.finditer(rf"<{tag}>(.*?)</{tag}>", text, re.DOTALL)
        ]
        return " ".join(hashes) or "'none'"

    policy = "; ".join(
        (
            "default-src 'none'",
            f"script-src {inline_hashes('script')}",
            f"style-src {inline_hashes('style')}",
            "connect-src 'self'",
            "img-src data:",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
        )
    )
    return AdminPage(body=body, content_security_policy=policy)


def make_handler(
    application: RelayApplication, admin_page: AdminPage | None = None
) -> type[BaseHTTPRequestHandler]:
    class RelayRequestHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = f"CreditInformRelay/{VERSION}"

        def do_OPTIONS(self) -> None:  # noqa: N802
            self.send_response(HTTPStatus.NO_CONTENT)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self) -> None:  # noqa: N802
            try:
                parsed = urllib.parse.urlsplit(self.path)
                path = parsed.path.rstrip("/") or "/"
                query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
                if path in ("/", "/admin"):
                    if admin_page is None:
                        raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "admin page is not installed")
                    self._send_page(admin_page)
                    return
                if path == "/health":
                    health = application.health()
                    self._send_json(
                        HTTPStatus.OK
                        if health["status"] == "ok"
                        else HTTPStatus.SERVICE_UNAVAILABLE,
                        health,
                    )
                    return
                if path == "/api/v1/alerts":
                    group_id = self._query_group_id(query)
                    self._require_group_auth(group_id)
                    client_id = self._query_identifier(query, "clientId")
                    after = self._query_integer(query, "after", default=0, minimum=0)
                    after_token = self._query_optional_hex_token(query, "afterToken")
                    wait_seconds = self._query_float(
                        query,
                        "wait",
                        default=0,
                        minimum=0,
                        maximum=application.max_poll_seconds,
                    )
                    (
                        events,
                        cursor,
                        cursor_token,
                        cursor_valid,
                        head_cursor,
                        head_event_uid,
                    ) = application.relay_state.poll_with_cursor_token(
                        group_id, client_id, after, after_token, wait_seconds
                    )
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "alerts": events,
                            "cursor": cursor,
                            "cursorToken": cursor_token,
                            "cursorValid": cursor_valid,
                            "headCursor": head_cursor,
                            "headEventUid": head_event_uid,
                            "serverInstanceId": application.relay_state.server_instance_id,
                            "serverTime": now_ms(),
                        },
                    )
                    return
                if path == "/api/v1/monitors":
                    group_id = self._query_group_id(query)
                    self._require_group_auth(group_id)
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "serverTime": now_ms(),
                            "config": application.monitor_manager.config_status(
                                probe_storage=True
                            ),
                            "monitors": application.monitor_manager.statuses_for_group(group_id),
                        },
                    )
                    return
                if path == "/api/v1/admin/monitors":
                    self._require_admin_auth()
                    group_id = self._query_group_id(query)
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "serverTime": now_ms(),
                            "keepPlaceholder": KEEP_PLACEHOLDER,
                            "monitors": application.monitor_manager.list_monitor_configs(
                                group_id
                            ),
                            # The admin token cannot read /api/v1/monitors, so live values ride along.
                            "statuses": application.monitor_manager.statuses_for_group(
                                group_id
                            ),
                        },
                    )
                    return
                if path == "/api/v1/status":
                    group_id = self._query_group_id(query)
                    self._require_group_auth(group_id)
                    head_cursor, head_event_uid, cursor_token = (
                        application.relay_state.cursor_state()
                    )
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "status": "ok",
                            "serverTime": now_ms(),
                            "serverInstanceId": application.relay_state.server_instance_id,
                            "groupId": group_id,
                            "cursor": head_cursor,
                            "cursorToken": cursor_token,
                            "headCursor": head_cursor,
                            "headEventUid": head_event_uid,
                            "clients": application.relay_state.client_counts(group_id),
                            "monitors": application.monitor_manager.summary(group_id),
                            "config": application.monitor_manager.config_status(
                                probe_storage=True
                            ),
                        },
                    )
                    return
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "endpoint not found")
            except ApiError as error:
                self._send_api_error(error)
            except Exception as error:
                self.log_error("unhandled GET error: %s", error)
                self._send_api_error(
                    ApiError(HTTPStatus.INTERNAL_SERVER_ERROR, "internal_error", "internal server error")
                )

        def do_POST(self) -> None:  # noqa: N802
            try:
                path = urllib.parse.urlsplit(self.path).path.rstrip("/") or "/"
                body = self._read_json_object()
                if path in ("/api/v1/clients/register", "/api/v1/clients/heartbeat"):
                    group_id = self._body_group_id(body)
                    self._require_group_auth(group_id)
                    client_id = self._body_identifier(body, "clientId")
                    client_name = require_text(
                        body.get("clientName"), "clientName", 128, default=client_id
                    )
                    operation = (
                        application.relay_state.register
                        if path.endswith("register")
                        else application.relay_state.heartbeat
                    )
                    client, cursor = operation(group_id, client_id, client_name)
                    head_cursor, head_event_uid, cursor_token = (
                        application.relay_state.cursor_state(cursor)
                    )
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "ok": True,
                            "serverTime": now_ms(),
                            "serverInstanceId": application.relay_state.server_instance_id,
                            "cursor": cursor,
                            "cursorToken": cursor_token,
                            "headCursor": head_cursor,
                            "headEventUid": head_event_uid,
                            "client": client,
                            "clients": application.relay_state.client_counts(group_id),
                        },
                    )
                    return
                if path == "/api/v1/alerts":
                    group_id = self._body_group_id(body)
                    self._require_group_auth(group_id)
                    sender_id = self._body_identifier(body, "senderId")
                    sender_name = require_text(
                        body.get("senderName"), "senderName", 128, default=sender_id
                    )
                    message = require_text(
                        body.get("message"), "message", 500, default="Manual alert"
                    )
                    application.relay_state.heartbeat(group_id, sender_id, sender_name)
                    event, recipients, online_recipients = application.relay_state.append_event(
                        group_id=group_id,
                        event_type="manual",
                        sender_id=sender_id,
                        sender_name=sender_name,
                        message=message,
                        excluded_client_id=sender_id,
                    )
                    self._send_json(
                        HTTPStatus.CREATED,
                        {
                            "accepted": True,
                            "serverTime": now_ms(),
                            "recipients": recipients,
                            "onlineRecipients": online_recipients,
                            "alert": event,
                        },
                    )
                    return
                if path == "/api/v1/admin/monitors":
                    self._require_admin_auth()
                    candidate = dict(body)
                    candidate["groupId"] = self._body_group_id(body)
                    monitor = application.monitor_manager.create_monitor_config(candidate)
                    self._send_json(
                        HTTPStatus.CREATED,
                        {
                            "ok": True,
                            "serverTime": now_ms(),
                            "keepPlaceholder": KEEP_PLACEHOLDER,
                            "monitor": monitor,
                        },
                    )
                    return
                if path == "/api/v1/admin/monitors/reorder":
                    self._require_admin_auth()
                    group_id = self._body_group_id(body)
                    monitors = application.monitor_manager.reorder_monitor_configs(
                        group_id,
                        body.get("expectedMonitorIds"),
                        body.get("monitorIds"),
                    )
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "ok": True,
                            "serverTime": now_ms(),
                            "keepPlaceholder": KEEP_PLACEHOLDER,
                            "monitors": monitors,
                        },
                    )
                    return
                if path == "/api/v1/admin/monitors/refresh":
                    self._require_admin_auth()
                    group_id = self._body_group_id(body)
                    self._send_json(
                        HTTPStatus.OK,
                        application.monitor_manager.refresh_monitor_configs(group_id),
                    )
                    return
                copy_match = re.fullmatch(
                    r"/api/v1/admin/monitors/([^/]+)/copy", path
                )
                if copy_match:
                    self._require_admin_auth()
                    monitor_id = require_identifier(
                        urllib.parse.unquote(copy_match.group(1)), "id"
                    )
                    group_id = self._body_group_id(body)
                    monitor = application.monitor_manager.copy_monitor_config(
                        group_id, monitor_id
                    )
                    self._send_json(
                        HTTPStatus.CREATED,
                        {
                            "ok": True,
                            "serverTime": now_ms(),
                            "keepPlaceholder": KEEP_PLACEHOLDER,
                            "monitor": monitor,
                        },
                    )
                    return
                admin_match = re.fullmatch(
                    r"/api/v1/admin/monitors/([^/]+)/test", path
                )
                if admin_match:
                    self._require_admin_auth()
                    monitor_id = require_identifier(
                        urllib.parse.unquote(admin_match.group(1)), "id"
                    )
                    group_id = self._body_group_id(body)
                    self._send_json(
                        HTTPStatus.OK,
                        application.monitor_manager.test_monitor_config(
                            group_id, monitor_id
                        ),
                    )
                    return
                if path == "/api/v1/config/reload":
                    self._require_admin_auth()
                    reloaded = application.monitor_manager.reload(force=True)
                    status = application.monitor_manager.config_status()
                    self._send_json(
                        HTTPStatus.OK if reloaded else HTTPStatus.BAD_REQUEST,
                        {"ok": reloaded, "serverTime": now_ms(), "config": status},
                    )
                    return
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "endpoint not found")
            except ValueError as error:
                self._send_api_error(ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", str(error)))
            except ApiError as error:
                self._send_api_error(error)
            except Exception as error:
                self.log_error("unhandled POST error: %s", error)
                self._send_api_error(
                    ApiError(HTTPStatus.INTERNAL_SERVER_ERROR, "internal_error", "internal server error")
                )

        def do_PUT(self) -> None:  # noqa: N802
            try:
                path = urllib.parse.urlsplit(self.path).path.rstrip("/") or "/"
                match = re.fullmatch(r"/api/v1/admin/monitors/([^/]+)", path)
                if not match:
                    raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "endpoint not found")
                self._require_admin_auth()
                monitor_id = require_identifier(urllib.parse.unquote(match.group(1)), "id")
                body = self._read_json_object()
                group_id = self._body_group_id(body)
                monitor = application.monitor_manager.update_monitor_config(
                    group_id, monitor_id, body
                )
                self._send_json(
                    HTTPStatus.OK,
                    {
                        "ok": True,
                        "serverTime": now_ms(),
                        "keepPlaceholder": KEEP_PLACEHOLDER,
                        "monitor": monitor,
                    },
                )
            except ValueError as error:
                self._send_api_error(
                    ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", str(error))
                )
            except ApiError as error:
                self._send_api_error(error)
            except Exception as error:
                self.log_error("unhandled PUT error: %s", error)
                self._send_api_error(
                    ApiError(
                        HTTPStatus.INTERNAL_SERVER_ERROR,
                        "internal_error",
                        "internal server error",
                    )
                )

        def do_DELETE(self) -> None:  # noqa: N802
            try:
                parsed = urllib.parse.urlsplit(self.path)
                path = parsed.path.rstrip("/") or "/"
                match = re.fullmatch(r"/api/v1/admin/monitors/([^/]+)", path)
                if not match:
                    raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "endpoint not found")
                self._require_admin_auth()
                monitor_id = require_identifier(urllib.parse.unquote(match.group(1)), "id")
                query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
                group_id = self._query_group_id(query)
                application.monitor_manager.delete_monitor_config(group_id, monitor_id)
                self._send_json(
                    HTTPStatus.OK, {"ok": True, "serverTime": now_ms()}
                )
            except ValueError as error:
                self._send_api_error(
                    ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", str(error))
                )
            except ApiError as error:
                self._send_api_error(error)
            except Exception as error:
                self.log_error("unhandled DELETE error: %s", error)
                self._send_api_error(
                    ApiError(
                        HTTPStatus.INTERNAL_SERVER_ERROR,
                        "internal_error",
                        "internal server error",
                    )
                )

        def _read_json_object(self) -> dict[str, Any]:
            try:
                content_length = int(self.headers.get("Content-Length", "0"))
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", "invalid Content-Length") from error
            if content_length <= 0 or content_length > MAX_REQUEST_BYTES:
                raise ApiError(
                    HTTPStatus.BAD_REQUEST,
                    "invalid_request",
                    f"JSON body must contain 1-{MAX_REQUEST_BYTES} bytes",
                )
            try:
                body = json.loads(self.rfile.read(content_length).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_json", "request body is not valid JSON") from error
            if not isinstance(body, dict):
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", "request body must be a JSON object")
            return body

        @staticmethod
        def _body_identifier(body: dict[str, Any], name: str) -> str:
            try:
                return require_identifier(body.get(name), name)
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", str(error)) from error

        def _default_group_id(self) -> str:
            group_id = application.monitor_manager.single_group_id()
            if group_id is None:
                raise ApiError(
                    HTTPStatus.BAD_REQUEST,
                    "group_required",
                    "groupId is required unless exactly one group is configured",
                )
            return group_id

        def _body_group_id(self, body: dict[str, Any]) -> str:
            if "groupId" in body:
                return self._body_identifier(body, "groupId")
            return self._default_group_id()

        @staticmethod
        def _query_identifier(query: dict[str, list[str]], name: str) -> str:
            try:
                return require_identifier(query.get(name, [None])[0], name)
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", str(error)) from error

        def _query_group_id(self, query: dict[str, list[str]]) -> str:
            if "groupId" in query:
                return self._query_identifier(query, "groupId")
            return self._default_group_id()

        @staticmethod
        def _query_integer(
            query: dict[str, list[str]], name: str, default: int, minimum: int
        ) -> int:
            raw_value = query.get(name, [str(default)])[0]
            try:
                value = int(raw_value)
            except (TypeError, ValueError) as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", f"{name} must be an integer") from error
            if value < minimum:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", f"{name} must be >= {minimum}")
            return value

        @staticmethod
        def _query_optional_hex_token(
            query: dict[str, list[str]], name: str
        ) -> str | None:
            values = query.get(name)
            if values is None:
                return None
            if len(values) != 1:
                raise ApiError(
                    HTTPStatus.BAD_REQUEST,
                    "invalid_request",
                    f"{name} must be specified once",
                )
            value = values[0]
            if not re.fullmatch(r"[0-9a-f]{32}", value):
                raise ApiError(
                    HTTPStatus.BAD_REQUEST,
                    "invalid_request",
                    f"{name} must be a 32-character lowercase hex string",
                )
            return value

        @staticmethod
        def _query_float(
            query: dict[str, list[str]],
            name: str,
            default: float,
            minimum: float,
            maximum: float,
        ) -> float:
            raw_value = query.get(name, [str(default)])[0]
            try:
                value = float(raw_value)
            except (TypeError, ValueError) as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_request", f"{name} must be a number") from error
            if not math.isfinite(value) or not minimum <= value <= maximum:
                raise ApiError(
                    HTTPStatus.BAD_REQUEST,
                    "invalid_request",
                    f"{name} must be between {minimum:g} and {maximum:g}",
                )
            return value

        def _bearer_token(self) -> str:
            authorization = self.headers.get("Authorization", "")
            prefix = "Bearer "
            if not authorization.startswith(prefix):
                return ""
            return authorization[len(prefix) :]

        def _require_group_auth(self, group_id: str) -> None:
            if not application.authorize(group_id, self._bearer_token()):
                raise ApiError(HTTPStatus.UNAUTHORIZED, "unauthorized", "invalid shared token")

        def _require_admin_auth(self) -> None:
            supplied = self._bearer_token()
            if not application.authorize_admin(supplied):
                raise ApiError(HTTPStatus.UNAUTHORIZED, "unauthorized", "invalid admin token")

        def _send_api_error(self, error: ApiError) -> None:
            headers = {"WWW-Authenticate": "Bearer"} if error.status == HTTPStatus.UNAUTHORIZED else None
            self._send_json(
                error.status,
                {"error": {"code": error.code, "message": error.message}},
                headers=headers,
            )

        def _send_json(
            self,
            status: int,
            document: dict[str, Any],
            headers: dict[str, str] | None = None,
        ) -> None:
            payload = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            if headers:
                for name, value in headers.items():
                    self.send_header(name, value)
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def _send_page(self, page: AdminPage) -> None:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page.body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Security-Policy", page.content_security_policy)
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.end_headers()
            try:
                self.wfile.write(page.body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, message_format: str, *args: Any) -> None:
            print(
                f"{self.log_date_time_string()} {self.client_address[0]} "
                f"{message_format % args}",
                file=sys.stderr,
            )

    return RelayRequestHandler


def create_http_server(
    host: str, port: int, application: RelayApplication
) -> RelayHTTPServer:
    return RelayHTTPServer((host, port), make_handler(application, load_admin_page()))


def positive_int_environment(name: str, default: int, minimum: int, maximum: int) -> int:
    raw_value = os.environ.get(name, str(default))
    try:
        value = int(raw_value)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def main() -> int:
    try:
        host = os.environ.get("HOST", "0.0.0.0")
        port = positive_int_environment("PORT", 8047, 1, 65535)
        heartbeat_timeout = positive_int_environment("HEARTBEAT_TIMEOUT_SECONDS", 90, 10, 3600)
        retention = positive_int_environment("EVENT_RETENTION_SECONDS", 86_400, 60, 2_592_000)
        max_events = positive_int_environment("MAX_EVENTS_PER_GROUP", 1000, 10, 100_000)
        max_poll = positive_int_environment("MAX_POLL_SECONDS", 30, 1, 60)
        config_file = os.environ.get("RELAY_CONFIG_FILE", "config.json")
        data_file = os.environ.get("RELAY_DATA_FILE", "data/relay-state.json").strip() or None
        monitor_store_file = os.environ.get(
            "MONITOR_STORE_FILE", "data/monitor-configs.json"
        ).strip() or None
        global_token = os.environ.get("RELAY_TOKEN", "").strip()
        admin_token = os.environ.get(
            "ADMIN_TOKEN", os.environ.get("RELAY_ADMIN_TOKEN", "")
        ).strip()

        relay_state = RelayState(
            data_file,
            heartbeat_timeout_seconds=heartbeat_timeout,
            event_retention_seconds=retention,
            max_events_per_group=max_events,
        )
        monitor_manager = MonitorManager(
            relay_state,
            config_file,
            managed_store_file=monitor_store_file,
            global_token=global_token,
        )
        if not monitor_manager.has_authentication():
            raise ValueError(
                "no shared token configured; set RELAY_TOKEN or add groups[].sharedToken to config.json"
            )
        if admin_token and monitor_manager.token_is_group_token(admin_token):
            raise ValueError("ADMIN_TOKEN must be different from every relay shared token")
        application = RelayApplication(
            relay_state,
            monitor_manager,
            max_poll_seconds=max_poll,
            admin_token=admin_token,
        )
        server = create_http_server(host, port, application)
        monitor_manager.start()

        def request_shutdown(_signal_number: int, _frame: Any) -> None:
            threading.Thread(target=server.shutdown, daemon=True).start()

        signal.signal(signal.SIGINT, request_shutdown)
        signal.signal(signal.SIGTERM, request_shutdown)
        print(f"CreditInform relay {VERSION} listening on http://{host}:{port}", file=sys.stderr)
        try:
            server.serve_forever(poll_interval=0.5)
        finally:
            server.server_close()
            monitor_manager.stop()
        return 0
    except ValueError as error:
        print(f"configuration error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
