"""OpenAI-compatible client against a local stub server (08 TASK-058, 07 §86, §112).

No real model is called: the stub records what the client sends and answers as scripted, so the
wire format, total timeout, error mapping, redirect refusal and key handling are checked locally.
"""

import json
import logging
import socket
import threading
import time
from collections.abc import Callable, Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from opspilot_ai.config import Settings
from opspilot_ai.errors import AiOutputInvalidError, LlmTimeoutError, LlmUnavailableError
from opspilot_ai.llm.client import LlmPrompt
from opspilot_ai.llm.fake import INVESTIGATION_TEMPLATE, REMEDIATION_TEMPLATE
from opspilot_ai.llm.openai_compatible import MAX_RESPONSE_BYTES, OpenAiCompatibleClient
from opspilot_ai.llm.prompts import INVESTIGATION_V1
from opspilot_ai.main import create_app

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "ai-runtime" / "v1"
API_KEY = "sk-stub-secret-key-0123456789"
TOKEN = "internal-test-token"
CONTEXT = {"incident": {"title": "统计延迟"}, "budget": {"remainingCapabilityCalls": 3}}

Handler = Callable[[BaseHTTPRequestHandler], None]


class Stub:
    """One local HTTP server; `answer` decides each response, `requests` records what arrived."""

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.answer: Handler = lambda h: _json(h, 200, _chat("{}"))
        stub = self

        class _Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802 - http.server naming
                length = int(self.headers.get("Content-Length", "0"))
                stub.requests.append(
                    {
                        "path": self.path,
                        "headers": dict(self.headers),
                        "body": json.loads(self.rfile.read(length)),
                    }
                )
                stub.answer(self)

            def log_message(self, *args: object) -> None:
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.server.daemon_threads = True
        self.base_url = f"http://127.0.0.1:{self.server.server_port}/v1"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def stub() -> Iterator[Stub]:
    server = Stub()
    yield server
    server.close()


def _chat(content: object, usage: dict | None = None) -> dict:
    body: dict = {"choices": [{"index": 0, "message": {"role": "assistant", "content": content}}]}
    if usage is not None:
        body["usage"] = usage
    return body


def _json(handler: BaseHTTPRequestHandler, status: int, body: object) -> None:
    data = json.dumps(body, ensure_ascii=False).encode()
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


def _client(base_url: str, timeout: float = 5.0, json_mode: bool = True) -> OpenAiCompatibleClient:
    return OpenAiCompatibleClient(base_url, API_KEY, "stub-model", timeout, json_mode)


def _prompt() -> LlmPrompt:
    return LlmPrompt(INVESTIGATION_TEMPLATE, CONTEXT)


def test_one_chat_completion_carries_the_versioned_prompt_and_the_context(stub: Stub) -> None:
    stub.answer = lambda h: _json(
        h, 200, _chat('{"intentType": "X"}', {"prompt_tokens": 812, "completion_tokens": 64})
    )

    completion = _client(stub.base_url).complete(_prompt())

    assert (completion.text, completion.prompt_tokens, completion.completion_tokens) == (
        '{"intentType": "X"}',
        812,
        64,
    )
    [request] = stub.requests
    assert request["path"] == "/v1/chat/completions"
    assert request["headers"]["Authorization"] == f"Bearer {API_KEY}"
    body = request["body"]
    assert body["model"] == "stub-model"
    assert body["messages"] == [
        {"role": "system", "content": INVESTIGATION_V1},
        {"role": "user", "content": json.dumps(CONTEXT, ensure_ascii=False)},
    ]
    assert body["response_format"] == {"type": "json_object"}
    assert (body["temperature"], body["stream"]) == (0, False)


def test_json_mode_can_be_disabled_and_usage_is_optional(stub: Stub) -> None:
    stub.answer = lambda h: _json(h, 200, _chat("{}", {"prompt_tokens": "many"}))

    completion = _client(stub.base_url, json_mode=False).complete(_prompt())

    assert "response_format" not in stub.requests[0]["body"]
    assert (completion.prompt_tokens, completion.completion_tokens) == (None, None)


def _silent(handler: BaseHTTPRequestHandler) -> None:
    time.sleep(3)


def _drip(handler: BaseHTTPRequestHandler) -> None:
    """Answers at once but sends the body one byte every 100 ms: no single read times out."""
    handler.send_response(200)
    handler.send_header("Content-Length", "1000")
    handler.end_headers()
    for _ in range(1000):
        try:
            handler.wfile.write(b" ")
            handler.wfile.flush()
        except OSError:
            return
        time.sleep(0.1)


@pytest.mark.parametrize("answer", [_silent, _drip], ids=["silent", "drip-feed"])
def test_the_timeout_bounds_the_whole_call(stub: Stub, answer: Handler) -> None:
    stub.answer = answer
    started = time.monotonic()

    with pytest.raises(LlmTimeoutError):
        _client(stub.base_url, timeout=0.6).complete(_prompt())

    assert time.monotonic() - started < 1.5
    assert len(stub.requests) == 1  # no retry


@pytest.mark.parametrize("status", [400, 401, 429, 500, 503])
def test_error_statuses_are_unavailable_without_retry_or_leaking_the_key(
    stub: Stub, status: int, caplog: pytest.LogCaptureFixture
) -> None:
    stub.answer = lambda h: _json(h, status, {"error": {"message": f"bad key {API_KEY}"}})
    caplog.set_level(logging.DEBUG)

    with pytest.raises(LlmUnavailableError) as raised:
        _client(stub.base_url).complete(_prompt())

    assert len(stub.requests) == 1
    assert API_KEY not in str(raised.value)
    assert API_KEY not in caplog.text


def test_redirects_are_not_followed_so_the_key_stays_with_the_configured_origin(
    stub: Stub,
) -> None:
    elsewhere = Stub()
    try:

        def redirect(handler: BaseHTTPRequestHandler) -> None:
            handler.send_response(307)
            handler.send_header("Location", f"{elsewhere.base_url}/chat/completions")
            handler.send_header("Content-Length", "0")
            handler.end_headers()

        stub.answer = redirect

        with pytest.raises(LlmUnavailableError):
            _client(stub.base_url).complete(_prompt())

        assert elsewhere.requests == []
    finally:
        elsewhere.close()


def test_the_timeout_also_bounds_a_blocked_dns_lookup(monkeypatch: pytest.MonkeyPatch) -> None:
    """B18-R1: getaddrinfo cannot be interrupted; the call waits for it only until the deadline."""
    lookups: list[str] = []

    def blocked(host: str, *args: object, **kwargs: object) -> list:
        lookups.append(host)
        time.sleep(2)
        raise socket.gaierror("resolver gave up")

    monkeypatch.setattr(socket, "getaddrinfo", blocked)
    started = time.monotonic()

    with pytest.raises(LlmTimeoutError):
        _client("https://llm.example/v1", timeout=0.2).complete(_prompt())

    assert time.monotonic() - started < 0.5
    assert lookups == ["llm.example"]


def test_the_timeout_also_bounds_a_silent_tls_handshake(monkeypatch: pytest.MonkeyPatch) -> None:
    """B18-R2: TCP connect takes 300 ms, then the server never answers the TLS ClientHello."""
    server = socket.create_server(("127.0.0.1", 0))
    port = server.getsockname()[1]
    held: list[socket.socket] = []

    def accept() -> None:
        while True:
            try:
                held.append(server.accept()[0])  # keep it open, never answer
            except OSError:
                return

    threading.Thread(target=accept, daemon=True).start()

    class SlowConnect(socket.socket):
        def connect(self, address: object) -> None:
            time.sleep(0.3)
            super().connect(address)

    monkeypatch.setattr(socket, "socket", SlowConnect)
    started = time.monotonic()
    try:
        with pytest.raises(LlmTimeoutError):
            _client(f"https://127.0.0.1:{port}/v1", timeout=0.5).complete(_prompt())
        elapsed = time.monotonic() - started
    finally:
        server.close()
        for sock in held:
            sock.close()

    assert elapsed < 0.7
    assert len(held) == 1  # the handshake really started on an accepted connection


def test_failed_dns_lookups_are_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    def failing(*args: object, **kwargs: object) -> list:
        raise socket.gaierror("Name or service not known")

    monkeypatch.setattr(socket, "getaddrinfo", failing)

    with pytest.raises(LlmUnavailableError):
        _client("https://llm.example/v1").complete(_prompt())


def test_unreachable_backends_are_unavailable() -> None:
    server = Stub()
    base_url = server.base_url
    server.close()

    with pytest.raises(LlmUnavailableError):
        _client(base_url).complete(_prompt())


def test_oversized_answers_are_rejected(stub: Stub) -> None:
    stub.answer = lambda h: _json(h, 200, _chat("x" * MAX_RESPONSE_BYTES))

    with pytest.raises(LlmUnavailableError):
        _client(stub.base_url).complete(_prompt())


@pytest.mark.parametrize(
    "body",
    [{"choices": []}, {"choices": [{"message": {"content": None}}]}, {"id": "x"}, ["x"]],
    ids=["no-choice", "null-content", "no-choices", "not-an-object"],
)
def test_answers_without_message_content_are_invalid_output(stub: Stub, body: object) -> None:
    stub.answer = lambda h: _json(h, 200, body)

    with pytest.raises(AiOutputInvalidError):
        _client(stub.base_url).complete(_prompt())


def test_templates_without_a_prompt_are_not_sent(stub: Stub) -> None:
    with pytest.raises(LlmUnavailableError):
        _client(stub.base_url).complete(LlmPrompt(REMEDIATION_TEMPLATE, CONTEXT))

    assert stub.requests == []


@pytest.mark.parametrize(
    "base_url",
    ["ftp://h/v1", "http:///v1", "https://user:pw@h/v1", "https://h/v1?x=1", "https://h/v1#f"],
)
def test_base_urls_must_be_plain_http_origins(base_url: str) -> None:
    with pytest.raises(ValueError):
        _client(base_url)


# ---------------------------------------------------------------- through the internal API


def _fixture(name: str, kind: str, stem: str) -> dict:
    path = CONTRACT / "fixtures" / name / kind / f"{stem}.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _api(base_url: str) -> TestClient:
    settings = Settings(
        model="stub-model",
        internal_token=TOKEN,
        llm_provider="openai-compatible",
        llm_base_url=base_url,
        llm_api_key=API_KEY,
        llm_timeout_seconds=5,
    )
    return TestClient(create_app(settings))


def test_a_real_model_answer_becomes_a_validated_step_with_call_metadata(
    stub: Stub, caplog: pytest.LogCaptureFixture
) -> None:
    intent = _fixture("investigation-step-response", "valid", "request-service-inspect")
    for echoed in ("protocolVersion", "runNo", "stepId"):
        intent.pop(echoed)
    stub.answer = lambda h: _json(
        h,
        200,
        _chat(
            json.dumps(intent, ensure_ascii=False), {"prompt_tokens": 900, "completion_tokens": 40}
        ),
    )
    request = _fixture("investigation-step-request", "valid", "full-context-all-descriptors")
    caplog.set_level(logging.DEBUG)

    response = _api(stub.base_url).post(
        "/internal/v1/investigation/step",
        json=request,
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert response.status_code == 200
    body = response.json()
    assert (body["runNo"], body["stepId"], body["intentType"]) == (2, 42, "REQUEST_CAPABILITY")
    assert response.headers["X-OpsPilot-Model-Provider"] == "openai-compatible"
    assert response.headers["X-OpsPilot-Model-Name"] == "stub-model"
    assert response.headers["X-OpsPilot-Prompt-Template-Version"] == "investigation-v1"
    assert response.headers["X-OpsPilot-Prompt-Tokens"] == "900"
    sent = json.loads(stub.requests[0]["body"]["messages"][1]["content"])
    assert sent == request
    assert API_KEY not in response.text + str(response.headers) + caplog.text


@pytest.mark.parametrize(
    ("answer", "status", "code"),
    [
        (lambda h: _json(h, 200, _chat("这不是 JSON")), 502, "AI_OUTPUT_INVALID"),
        (lambda h: _json(h, 200, _chat('{"intentType": "RESTART"}')), 502, "AI_OUTPUT_INVALID"),
        (lambda h: _json(h, 503, {"error": "overloaded"}), 503, "AI_RUNTIME_UNAVAILABLE"),
    ],
    ids=["not-json", "unknown-intent", "backend-down"],
)
def test_model_failures_map_to_fixed_errors(
    stub: Stub, answer: Handler, status: int, code: str
) -> None:
    stub.answer = answer
    request = _fixture("investigation-step-request", "valid", "full-context-all-descriptors")

    response = _api(stub.base_url).post(
        "/internal/v1/investigation/step",
        json=request,
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert (response.status_code, response.json()["code"]) == (status, code)
    assert API_KEY not in response.text


# ---------------------------------------------------------------- settings


def test_openai_compatible_settings_require_url_and_key_and_hide_the_key() -> None:
    with pytest.raises(RuntimeError, match="BASE_URL"):
        Settings(model="m", llm_provider="openai-compatible", llm_api_key=API_KEY)
    with pytest.raises(RuntimeError, match="API_KEY"):
        Settings(model="m", llm_provider="openai-compatible", llm_base_url="https://h/v1")
    with pytest.raises(RuntimeError, match="TIMEOUT"):
        Settings(model="m", llm_timeout_seconds=0)
    settings = Settings(
        model="m",
        llm_provider="openai-compatible",
        llm_base_url="https://h/v1",
        llm_api_key=API_KEY,
    )
    assert API_KEY not in repr(settings)


def test_settings_are_read_from_the_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    for name, value in {
        "OPSPILOT_AI_MODEL": "deepseek-chat",
        "OPSPILOT_AI_RUNTIME_TOKEN": TOKEN,
        "OPSPILOT_AI_LLM_PROVIDER": "openai-compatible",
        "OPSPILOT_AI_LLM_BASE_URL": "https://llm.example/v1",
        "OPSPILOT_AI_LLM_API_KEY": API_KEY,
        "OPSPILOT_AI_LLM_TIMEOUT_SECONDS": "30",
        "OPSPILOT_AI_LLM_JSON_MODE": "false",
    }.items():
        monkeypatch.setenv(name, value)

    settings = Settings.from_env()

    assert (settings.llm_base_url, settings.llm_api_key, settings.llm_timeout_seconds) == (
        "https://llm.example/v1",
        API_KEY,
        30.0,
    )
    assert settings.llm_json_mode is False


def test_rejected_answers_are_logged_by_field_path_without_values(
    stub: Stub, caplog: pytest.LogCaptureFixture
) -> None:
    """Diagnosing a real model: which fields broke protocol v1, never the model's text (05 §93)."""
    stub.answer = lambda h: _json(
        h,
        200,
        _chat(
            json.dumps(
                {
                    "intentType": "PROPOSE_HYPOTHESIS",
                    "proposeHypothesis": {
                        "title": 123,
                        "sk-marker-field": "model-text-marker",
                        "description": {"nested-marker-key": 0.9},
                    },
                }
            )
        ),
    )
    request = _fixture("investigation-step-request", "valid", "full-context-all-descriptors")
    caplog.set_level(logging.WARNING)

    response = _api(stub.base_url).post(
        "/internal/v1/investigation/step",
        json=request,
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert response.json()["code"] == "AI_OUTPUT_INVALID"
    # known field names are kept; keys the model made up become a fixed placeholder (B18-R2)
    assert "PROPOSE_HYPOTHESIS.proposeHypothesis.title:string_type" in caplog.text
    assert "PROPOSE_HYPOTHESIS.proposeHypothesis.<unknown>:extra_forbidden" in caplog.text
    assert "PROPOSE_HYPOTHESIS.proposeHypothesis.description:string_type" in caplog.text
    for leaked in ("sk-marker-field", "nested-marker-key", "model-text-marker", "0.9", "123"):
        assert leaked not in caplog.text
