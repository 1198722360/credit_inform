#!/usr/bin/env python3
"""Run a real two-client relay smoke test against a running server."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Any


def request_json(
    base_url: str,
    token: str,
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    timeout: float = 10,
) -> dict[str, Any]:
    payload = None
    headers = {"Accept": "application/json", "Authorization": f"Bearer {token}"}
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=payload,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {detail}") from error


def register(
    base_url: str, token: str, group_id: str | None, client_id: str
) -> dict[str, Any]:
    body = {"clientId": client_id}
    if group_id is not None:
        body["groupId"] = group_id
    return request_json(
        base_url,
        token,
        "POST",
        "/api/v1/clients/register",
        body,
    )


def poll_path(
    group_id: str | None,
    client_id: str,
    cursor: int,
    cursor_token: str | None,
    wait: int,
) -> str:
    query: dict[str, str | int] = {"clientId": client_id, "after": cursor, "wait": wait}
    if group_id is not None:
        query["groupId"] = group_id
    if cursor_token is not None:
        query["afterToken"] = cursor_token
    return "/api/v1/alerts?" + urllib.parse.urlencode(query)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="Relay base URL")
    parser.add_argument(
        "--group", help="Legacy group ID; omit when the server has one configured group"
    )
    parser.add_argument("--token", required=True, help="Shared token")
    args = parser.parse_args()

    suffix = uuid.uuid4().hex[:10]
    sender_id = f"smoke-sender-{suffix}"
    receiver_id = f"smoke-receiver-{suffix}"
    sender = register(args.url, args.token, args.group, sender_id)
    receiver = register(args.url, args.token, args.group, receiver_id)
    if sender.get("serverInstanceId") != receiver.get("serverInstanceId"):
        raise RuntimeError("registrations returned different server instances")

    receiver_path = poll_path(
        args.group,
        receiver_id,
        int(receiver["cursor"]),
        receiver.get("cursorToken"),
        3,
    )
    message = f"值守报警冒烟测试 {suffix}"
    with ThreadPoolExecutor(max_workers=1) as executor:
        waiting_poll = executor.submit(
            request_json, args.url, args.token, "GET", receiver_path, None, 6
        )
        time.sleep(0.15)
        alert_body = {"senderId": sender_id, "message": message}
        if args.group is not None:
            alert_body["groupId"] = args.group
        sent = request_json(
            args.url,
            args.token,
            "POST",
            "/api/v1/alerts",
            alert_body,
        )
        received = waiting_poll.result(timeout=7)

    sent_alert = sent["alert"]
    event_uid = sent_alert.get("eventUid")
    if not event_uid or sent_alert.get("type") != "manual":
        raise RuntimeError("send response did not contain a valid manual alert")
    if not any(alert.get("eventUid") == event_uid for alert in received.get("alerts", [])):
        raise RuntimeError("receiver long poll did not return the sent alert")
    if received.get("cursorValid") is not True:
        raise RuntimeError("server rejected the registered receiver cursor")
    if int(received.get("cursor", 0)) > int(received.get("headCursor", 0)):
        raise RuntimeError("receiver cursor advanced beyond headCursor")
    received_token = received.get("cursorToken")
    if received["cursor"] and (
        not isinstance(received_token, str)
        or len(received_token) != 32
        or any(character not in "0123456789abcdef" for character in received_token)
    ):
        raise RuntimeError("receiver response did not contain a valid cursor token")
    token_round_trip = request_json(
        args.url,
        args.token,
        "GET",
        poll_path(
            args.group,
            receiver_id,
            int(received["cursor"]),
            received_token,
            0,
        ),
    )
    if token_round_trip.get("cursorValid") is not True:
        raise RuntimeError("server rejected the cursor token it just issued")
    if int(token_round_trip.get("cursor", -1)) != int(received["cursor"]):
        raise RuntimeError("cursor changed during the token round trip")
    if token_round_trip.get("alerts"):
        raise RuntimeError("token round trip unexpectedly replayed the acknowledged alert")

    own = request_json(
        args.url,
        args.token,
        "GET",
        poll_path(
            args.group,
            sender_id,
            int(sender["cursor"]),
            sender.get("cursorToken"),
            0,
        ),
    )
    if own.get("cursorValid") is not True:
        raise RuntimeError("server rejected the registered sender cursor")
    if any(alert.get("eventUid") == event_uid for alert in own.get("alerts", [])):
        raise RuntimeError("sender received its own alert")

    print(
        json.dumps(
            {
                "ok": True,
                "serverInstanceId": received.get("serverInstanceId"),
                "eventId": sent_alert["id"],
                "eventUid": event_uid,
                "onlineRecipients": sent.get("onlineRecipients", 0),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
