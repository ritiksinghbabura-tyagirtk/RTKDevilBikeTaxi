import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app import create_app
from app.extensions import db
from app.models import User, RiderProfile, VehicleCategory
from flask_jwt_extended import create_access_token

def test_admin_and_rider_workflow():
    app = create_app()
    app.config["TESTING"] = True
    client = app.test_client()

    with app.app_context():
        db.create_all()

        # 1. Check or Create Admin User safely
        admin = User.query.filter_by(email="admin@taxibike.com").first()
        if not admin:
            admin = User(full_name="Super Admin", email="admin@taxibike.com", phone="0000000000", role="admin", is_email_verified=True)
            admin.set_password("AdminPass123")
            db.session.add(admin)
            db.session.commit()

        # 2. Create Unique Test Rider for this test run
        rider_email = "testrider_workflow@taxibike.com"
        rider_user = User.query.filter_by(email=rider_email).first()
        if not rider_user:
            rider_user = User(full_name="Test Rider", email=rider_email, phone="9111111222", role="rider", is_email_verified=True)
            rider_user.set_password("RiderPass123")
            db.session.add(rider_user)
            db.session.flush()

            rider_prof = RiderProfile(user_id=rider_user.id, vehicle_number="HR26AB9999", driving_license="DL992023999", verification_status="pending")
            db.session.add(rider_prof)
            db.session.commit()

        # Generate tokens
        admin_token = create_access_token(identity=str(admin.id), additional_claims={"role": "admin"})
        rider_token = create_access_token(identity=str(rider_user.id), additional_claims={"role": "rider"})

        # 3. Test Admin Get Pending Riders
        headers = {"Authorization": f"Bearer {admin_token}"}
        res = client.get("/api/v1/admin/riders/pending", headers=headers)
        assert res.status_code == 200

        # 4. Test Admin Approve Rider
        res_approve = client.post(f"/api/v1/admin/riders/{rider_user.id}/verify", json={"status": "approved"}, headers=headers)
        assert res_approve.status_code == 200
        assert rider_user.rider_profile.verification_status == "approved"

        # 5. Test Rider Update Location / Online Status
        rider_headers = {"Authorization": f"Bearer {rider_token}"}
        res_loc = client.post("/api/v1/rider/location", json={"lat": 28.6139, "lng": 77.2090, "is_online": True}, headers=rider_headers)
        assert res_loc.status_code == 200
        assert res_loc.json["is_online"] is True
