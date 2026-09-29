"""OpenAI-compatible Chat Completions client (02 §11, 07 §77-§86), standard library only.

One POST to `{baseUrl}/chat/completions` per step: the versioned system prompt plus Java's curated
context as the user message. The whole call - DNS lookup, connect, TLS, request and response body -
is bounded by one total timeout; there is no retry (07 §86) and redirects are never followed, so the
API key is only ever sent to the configured origin. The key never appears in exceptions, logs or
responses.
"""

import http.client
import json
import logging
import socket
import ssl
import threading
import time
from typing import Any
from urllib.parse import urlsplit

from opspilot_ai.errors import AiOutputInvalidError, LlmTimeoutError, LlmUnavailableError
from opspilot_ai.llm.client import LlmCompletion, LlmPrompt
from opspilot_ai.llm.prompts import SYSTEM_PROMPTS

MAX_RESPONSE_BYTES = 1024 * 1024

log = logging.getLogger(__name__)


class OpenAiCompatibleClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float,
        json_mode: bool = True,
    ) -> None:
        parts = urlsplit(base_url)
        if (
            parts.scheme not in ("http", "https")
            or not parts.hostname
            or parts.username
            or parts.password
            or parts.query
            or parts.fragment
        ):
            raise ValueError("LLM base URL must be http(s)://host[:port][/path]")
        self._https = parts.scheme == "https"
        self._host = parts.hostname
        self._port = parts.port
        self._path = parts.path.rstrip("/") + "/chat/completions"
        self._api_key = api_key
        self._model = model
        self._timeout = timeout_seconds
        self._json_mode = json_mode

    def complete(self, prompt: LlmPrompt) -> LlmCompletion:
        system = SYSTEM_PROMPTS.get(prompt.template_version)
        if system is None:
            # remediation-v1 is written in TASK-063; until then a real model is not asked
            raise LlmUnavailableError(f"no prompt for template {prompt.template_version}")
        request: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(prompt.context, ensure_ascii=False)},
            ],
            "temperature": 0,
            "stream": False,
        }
        if self._json_mode:
            request["response_format"] = {"type": "json_object"}
        status, body = self._post(json.dumps(request, ensure_ascii=False).encode("utf-8"))
        if not 200 <= status < 300:
            log.warning("LLM backend answered HTTP %d", status)
            raise LlmUnavailableError(f"model backend answered HTTP {status}")
        return _completion(body)

    def _post(self, body: bytes) -> tuple[int, bytes]:
        """Status and body; a watchdog shuts the socket down at the total timeout.

        The connection is opened here, not by http.client, so every phase stays under the
        deadline: DNS in its own thread, waited for only until the deadline (getaddrinfo cannot be
        interrupted, B18-R1); TCP connect and the TLS handshake with the time left, each socket
        registered with the watchdog before it blocks (B18-R2). http.client then only frames
        HTTP/1.1 over that socket. The watchdog holds the sockets itself: http.client drops
        `connection.sock` when a response will close the connection while the response keeps
        reading from that socket.
        """
        deadline = time.monotonic() + self._timeout
        port = self._port or (443 if self._https else 80)
        expired = threading.Event()
        sockets: list[socket.socket] = []

        def expire() -> None:
            expired.set()
            for sock in sockets:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass  # already closed

        watchdog = threading.Timer(self._timeout, expire)
        watchdog.daemon = True
        watchdog.start()
        connection = _OpenedConnection(self._host, port, 443 if self._https else 80)
        try:
            addresses = _resolve(self._host, port, deadline)
            sock = _connect(addresses, deadline, sockets, expired)
            if self._https:
                sock = _handshake(sock, self._host, deadline, sockets, expired)
            connection.sock = sock
            connection.request(
                "POST",
                self._path,
                body=body,
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                },
            )
            response = connection.getresponse()
            data = response.read(MAX_RESPONSE_BYTES + 1)
            status = response.status
        except (OSError, ValueError, http.client.HTTPException) as error:
            # ValueError: an operation on a socket the watchdog has already shut down
            if expired.is_set() or isinstance(error, TimeoutError):
                raise LlmTimeoutError("model backend did not answer in time") from None
            log.warning("LLM backend unreachable: %s", type(error).__name__)
            raise LlmUnavailableError("model backend is unreachable") from None
        finally:
            watchdog.cancel()
            connection.close()
        if expired.is_set():
            raise LlmTimeoutError("model backend did not answer in time")
        if len(data) > MAX_RESPONSE_BYTES:
            raise LlmUnavailableError("model backend response is too large")
        return status, data


def _resolve(host: str, port: int, deadline: float) -> list[tuple[Any, ...]]:
    """getaddrinfo in a daemon thread, waited for until the deadline only.

    A lookup that outlives the deadline is abandoned (it ends when the system resolver gives up);
    the call itself returns on time.
    """
    outcome: list[Any] = []
    done = threading.Event()

    def lookup() -> None:
        try:
            outcome.append(socket.getaddrinfo(host, port, type=socket.SOCK_STREAM))
        except OSError as error:
            outcome.append(error)
        finally:
            done.set()

    threading.Thread(target=lookup, name="llm-dns", daemon=True).start()
    if not done.wait(max(0.0, deadline - time.monotonic())):
        raise TimeoutError
    if isinstance(outcome[0], OSError):
        raise outcome[0]
    return outcome[0]


class _OpenedConnection(http.client.HTTPConnection):
    """HTTP/1.1 framing over a socket opened within the deadline; it never connects by itself.

    `default_port` only decides whether the Host header carries the port (443 for https).
    """

    def __init__(self, host: str, port: int, default_port: int) -> None:
        self.default_port = default_port
        super().__init__(host, port)

    def connect(self) -> None:
        raise OSError("connection was not opened")


def _remaining(deadline: float, expired: threading.Event) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0 or expired.is_set():
        raise TimeoutError
    return remaining


def _connect(
    addresses: list[tuple[Any, ...]],
    deadline: float,
    sockets: list[socket.socket],
    expired: threading.Event,
) -> socket.socket:
    """Tries each resolved address with the time left; sockets are registered with the watchdog."""
    last: OSError | None = None
    for family, kind, proto, _, address in addresses:
        remaining = _remaining(deadline, expired)
        sock = socket.socket(family, kind, proto)
        sockets.append(sock)
        try:
            sock.settimeout(remaining)
            sock.connect(address)
            return sock
        except OSError as error:
            sock.close()
            last = error
    raise last if last is not None else OSError("no address to connect to")


def _handshake(
    sock: socket.socket,
    host: str,
    deadline: float,
    sockets: list[socket.socket],
    expired: threading.Event,
) -> ssl.SSLSocket:
    """TLS with certificate and host name verification (the default context, SNI = host).

    Wrapping takes the file descriptor over from `sock`, so the SSLSocket is registered with the
    watchdog before the handshake, and the handshake gets only the time left.
    """
    context = ssl.create_default_context()
    context.set_alpn_protocols(["http/1.1"])
    tls = context.wrap_socket(sock, server_hostname=host, do_handshake_on_connect=False)
    sockets.append(tls)
    tls.settimeout(_remaining(deadline, expired))  # also catches expiry before registration
    tls.do_handshake()
    return tls


def _completion(body: bytes) -> LlmCompletion:
    """choices[0].message.content plus usage when reported; anything else is invalid output."""
    try:
        document = json.loads(body)
        content = document["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError) as error:
        raise AiOutputInvalidError("model backend response has no message content") from error
    if not isinstance(content, str):
        raise AiOutputInvalidError("model backend response has no message content")
    usage = document.get("usage") if isinstance(document, dict) else None
    usage = usage if isinstance(usage, dict) else {}
    return LlmCompletion(
        content,
        _token_count(usage.get("prompt_tokens")),
        _token_count(usage.get("completion_tokens")),
    )


def _token_count(value: object) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else None
