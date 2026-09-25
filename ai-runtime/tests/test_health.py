from fastapi.testclient import TestClient

from opspilot_ai.config import Settings
from opspilot_ai.main import create_app


def test_health_reports_configured_model_without_credentials() -> None:
    client = TestClient(create_app(Settings(model="test-model")))

    response = client.get("/internal/v1/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "UP",
        "modelProvider": "openai-compatible",
        "model": "test-model",
    }
