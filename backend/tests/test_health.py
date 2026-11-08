import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app import create_app

def test_health_endpoint():
    app = create_app()
    app.config["TESTING"] = True

    client = app.test_client()
    response = client.get("/api/v1/health")

    assert response.status_code == 200
    assert response.json["status"] == "ok"
    assert response.json["service"] == "RTKDevilBikeTaxi API"
