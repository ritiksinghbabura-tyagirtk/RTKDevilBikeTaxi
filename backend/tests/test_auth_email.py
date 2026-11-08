import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app import create_app
from app.extensions import db
from app.models import User, EmailVerificationToken

def test_customer_registration_and_email_verification():
    app = create_app()
    app.config["TESTING"] = True

    client = app.test_client()

    with app.app_context():
        db.drop_all()
        db.create_all()

        # 1. Register Customer
        reg_res = client.post("/api/v1/auth/register/customer", json={
            "full_name": "Test Customer",
            "email": "testuser@example.com",
            "phone": "9876543210",
            "password": "Password123"
        })
        assert reg_res.status_code == 201

        # 2. Check token in DB
        user = User.query.filter_by(email="testuser@example.com").first()
        assert user is not None
        assert user.is_email_verified is False

        tok = EmailVerificationToken.query.filter_by(user_id=user.id).first()
        assert tok is not None

        # 3. Try Login before verify (should fail with 403)
        login_fail = client.post("/api/v1/auth/login", json={
            "email": "testuser@example.com",
            "password": "Password123"
        })
        assert login_fail.status_code == 403

        # 4. Verify Email
        verify_res = client.get(f"/api/v1/auth/verify-email?token={tok.token}")
        assert verify_res.status_code == 200

        # 5. Login after verify (should succeed)
        login_success = client.post("/api/v1/auth/login", json={
            "email": "testuser@example.com",
            "password": "Password123"
        })
        assert login_success.status_code == 200
        assert "access_token" in login_success.json
