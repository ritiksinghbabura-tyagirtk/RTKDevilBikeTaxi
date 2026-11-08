import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app import create_app
from app.extensions import db

def test_categories_and_fare_estimation():
    app = create_app()
    app.config["TESTING"] = True
    client = app.test_client()

    with app.app_context():
        db.create_all()

        # 1. Test Categories Endpoint
        cat_res = client.get("/api/v1/rides/categories")
        assert cat_res.status_code == 200
        assert len(cat_res.json) >= 3

        # 2. Test Fare Estimation
        est_res = client.post("/api/v1/rides/estimate", json={
            "pickup_lat": 28.6139,
            "pickup_lng": 77.2090,
            "drop_lat": 28.7041,
            "drop_lng": 77.1025
        })
        assert est_res.status_code == 200
        assert "estimates" in est_res.json
        assert est_res.json["distance_km"] > 0
