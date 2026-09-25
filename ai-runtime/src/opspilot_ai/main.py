from fastapi import FastAPI

from opspilot_ai.api import health
from opspilot_ai.config import Settings


def create_app(settings: Settings) -> FastAPI:
    app = FastAPI(title="OpsPilot AI Runtime", version="0.1.0")
    app.state.settings = settings
    app.include_router(health.router)
    return app


def app_from_env() -> FastAPI:
    return create_app(Settings.from_env())
