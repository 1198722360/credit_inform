"""GateWay open API transport. Credentials and cryptography stay on the server."""

from __future__ import annotations

import base64
import json
import re
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
from functools import lru_cache
from typing import Any

MAX_REQUEST_BYTES = 32 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
ACCESS_KEY_RE = re.compile(r"gwak_[0-9a-f]{24}\Z")
ENV_NAME_RE = re.compile(r"(?!gwsk_)[A-Za-z_][A-Za-z0-9_]{0,127}\Z")
PATHS = {f"/open/v1/{name}/query" for name in ("balance", "nodes", "logs", "bans")}
ERROR_CODES = {
    "INVALID_REQUEST", "TIMESTAMP_EXPIRED", "INVALID_ACCESS_KEY", "AUTH_FAILED",
    "NONCE_REPLAYED", "IP_NOT_ALLOWED", "RATE_LIMITED", "NOT_FOUND",
    "SCOPE_DENIED", "INVALID_PARAMS", "SESSION_NOT_FOUND", "UNAVAILABLE",
}


class GatewayError(Exception):
    def __init__(self, message: str, http_code: int | None = None, retry_after: int = 0):
        super().__init__(message)
        self.http_code = http_code
        self.retry_after = retry_after


def validate_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme != "https" or not parsed.hostname
        or parsed.username is not None or parsed.password is not None
        or "?" in url or "#" in url or parsed.path not in PATHS
    ):
        raise ValueError("GateWay URL must use HTTPS and an exact /open/v1/{balance,nodes,logs,bans}/query path without query, fragment or user info")
    return parsed.path


def business_params(body: Any) -> dict[str, Any]:
    if isinstance(body, str):
        try:
            body = json.loads(body)
        except (ValueError, TypeError) as error:
            raise ValueError("GateWay body must be a JSON object") from error
    if body is None:
        return {}
    if not isinstance(body, dict):
        raise ValueError("GateWay body must be a JSON object")
    return body


@lru_cache(maxsize=32)
def derive_keys(access_key: str, secret_key: str) -> tuple[bytes, bytes]:
    if not ACCESS_KEY_RE.fullmatch(access_key):
        raise ValueError("GateWay AK must be gwak_ followed by 24 lowercase hex characters")
    if not re.fullmatch(r"gwsk_[A-Za-z0-9_-]{43}", secret_key):
        raise ValueError("GateWay SK format is invalid")
    raw = base64.urlsafe_b64decode(secret_key[5:] + "=")
    if len(raw) != 32:
        raise ValueError("GateWay SK must decode to 32 bytes")
    try:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    except ImportError as error:
        raise GatewayError("GateWay requires cryptography; install server/requirements.txt") from error
    return tuple(
        HKDF(algorithm=hashes.SHA256(), length=32, salt=access_key.encode("ascii"), info=info).derive(raw)
        for info in (b"gw-open/v1/req", b"gw-open/v1/resp")
    )


def request_aad(path: str, access_key: str, timestamp: str, nonce: str) -> bytes:
    return "\n".join(("GW1-REQ", "POST", path, access_key, timestamp, nonce)).encode("ascii")


def response_aad(access_key: str, nonce: str) -> bytes:
    return "\n".join(("GW1-RESP", access_key, nonce)).encode("ascii")


def seal(key: bytes, plaintext: bytes, aad: bytes, *, iv: bytes | None = None) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    # Explicit IV is only for the published interoperability vectors in tests.
    iv = secrets.token_bytes(12) if iv is None else iv
    return base64.b64encode(iv + AESGCM(key).encrypt(iv, plaintext, aad)).decode("ascii")


def open_payload(key: bytes, payload: str, aad: bytes) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    raw = base64.b64decode(payload, validate=True)
    if len(raw) < 28:
        raise ValueError("invalid encrypted payload")
    return AESGCM(key).decrypt(raw[:12], raw[12:], aad)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def call(url: str, access_key: str, secret_key: str, params: Any, timeout: int) -> tuple[int, dict[str, Any]]:
    """Return the verified response envelope; never expose upstream message/body text."""
    path = validate_url(url)
    req_key, resp_key = derive_keys(access_key, secret_key)
    nonce, timestamp = secrets.token_hex(16), str(int(time.time()))
    try:
        plaintext = json.dumps(business_params(params), ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (ValueError, TypeError) as error:
        raise GatewayError("GateWay body must be a JSON object containing valid JSON values") from error
    # Check before encryption too, to avoid allocating arbitrarily large envelopes.
    if len(plaintext) > MAX_REQUEST_BYTES:
        raise GatewayError("GateWay request exceeds 32 KiB")
    payload = seal(req_key, plaintext, request_aad(path, access_key, timestamp, nonce))
    body = json.dumps({"payload": payload}, separators=(",", ":")).encode("ascii")
    if len(body) > MAX_REQUEST_BYTES:
        raise GatewayError("GateWay encrypted request exceeds 32 KiB")
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json", "Accept": "application/json",
        "X-Gw-Access-Key": access_key, "X-Gw-Timestamp": timestamp, "X-Gw-Nonce": nonce,
    })
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        try:
            response = opener.open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.code
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            retry_text = response.headers.get("Retry-After", "0")
            retry_after = min(int(retry_text), 86400) if re.fullmatch(r"[0-9]{1,9}", retry_text) else 0
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise GatewayError("GateWay connection failed", None) from error
    if len(raw) > MAX_RESPONSE_BYTES:
        raise GatewayError("GateWay response exceeds 1 MiB", status)
    try:
        envelope = json.loads(raw)
        if not isinstance(envelope, dict):
            raise ValueError()
        encrypted = "payload" in envelope
        result = json.loads(open_payload(resp_key, envelope["payload"], response_aad(access_key, nonce))) if encrypted else envelope
        if not isinstance(result, dict) or not isinstance(result.get("code"), str):
            raise ValueError()
    except Exception as error:
        raise GatewayError("GateWay response is invalid or failed authentication", status) from error
    if result["code"] != "OK" or not encrypted or status != 200:
        code = result["code"] if result["code"] in ERROR_CODES else "API_ERROR"
        request_id = envelope.get("request_id", "")
        suffix = f"; request_id={request_id}" if isinstance(request_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", request_id) else ""
        raise GatewayError(f"GateWay {code} (HTTP {status}){suffix}", status, retry_after)
    if not isinstance(result.get("data"), dict):
        raise GatewayError("GateWay response data must be an object", status)
    return status, result
