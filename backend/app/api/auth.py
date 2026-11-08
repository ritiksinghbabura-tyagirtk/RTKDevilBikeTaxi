from datetime import datetime, timezone
from flask import request, jsonify
from flask_jwt_extended import (
    create_access_token, jwt_required, get_jwt_identity
)
from email_validator import validate_email, EmailNotValidError

from app.api import api_bp
from app.extensions import db
from app.models import User, RiderProfile, EmailVerificationToken
from app.services.email_service import send_verification_email


def clean_text(value):
    return value.strip() if isinstance(value, str) else ""


def register_user(data, role):
    full_name = clean_text(data.get("full_name"))
    email = clean_text(data.get("email")).lower()
    phone = clean_text(data.get("phone"))
    password = data.get("password")

    if not all([full_name, email, phone, password]):
        return jsonify({"error": "Name, email, phone and password are required"}), 400

    if len(full_name) > 100 or len(phone) > 20 or len(password) < 8:
        return jsonify({"error": "Invalid name/phone or password must be at least 8 characters"}), 400

    try:
        email = validate_email(email, check_deliverability=False).normalized
    except EmailNotValidError as exc:
        return jsonify({"error": str(exc)}), 400

    if User.query.filter((User.email == email) | (User.phone == phone)).first():
        return jsonify({"error": "Email or phone already registered"}), 409

    user = User(
        full_name=full_name,
        email=email,
        phone=phone,
        role=role,
        is_email_verified=False,
        is_active=True
    )
    user.set_password(password)
    db.session.add(user)

    if role == "rider":
        vehicle_number = clean_text(data.get("vehicle_number")).upper()
        driving_license = clean_text(data.get("driving_license"))

        if not vehicle_number or not driving_license:
            db.session.rollback()
            return jsonify({"error": "Vehicle number and driving license are required"}), 400

        db.session.flush()
        db.session.add(RiderProfile(
            user_id=user.id,
            vehicle_number=vehicle_number,
            driving_license=driving_license,
            verification_status="pending",
            is_online=False
        ))

    try:
        db.session.commit()
    except Exception:
        db.session.rollback()
        return jsonify({"error": "Registration could not be completed"}), 500

    email_sent = False
    email_message = "Email service is not available. Contact support."

    try:
        email_sent, email_message = send_verification_email(user)
    except Exception:
        db.session.rollback()

    return jsonify({
        "message": "Registration successful. Email verification is required.",
        "user_id": user.id,
        "email_sent": bool(email_sent),
        "email_message": email_message if not email_sent else "Verification email sent"
    }), 201


@api_bp.post("/auth/register/customer")
def register_customer():
    return register_user(request.get_json(silent=True) or {}, "customer")


@api_bp.post("/auth/register/rider")
def register_rider():
    return register_user(request.get_json(silent=True) or {}, "rider")


@api_bp.get("/auth/verify-email")
def verify_email():
    token_str = clean_text(request.args.get("token"))

    if not token_str:
        return jsonify({"error": "Verification token is required"}), 400

    token = EmailVerificationToken.query.filter_by(
        token=token_str, used=False
    ).first()

    if not token:
        return jsonify({"error": "Invalid or already used verification token"}), 400

    expires = token.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)

    if expires < datetime.now(timezone.utc):
        return jsonify({"error": "Verification token has expired"}), 400

    token.used = True
    token.user.is_email_verified = True
    db.session.commit()

    return jsonify({
        "status": "success",
        "message": "Email verified successfully. You can now login."
    }), 200


@api_bp.post("/auth/login")
def login():
    data = request.get_json(silent=True) or {}
    email = clean_text(data.get("email")).lower()
    password = data.get("password")

    if not email or not isinstance(password, str) or not password:
        return jsonify({"error": "Email and password are required"}), 400

    user = User.query.filter_by(email=email).first()

    if not user or not user.check_password(password):
        return jsonify({"error": "Invalid email or password"}), 401

    if not user.is_active:
        return jsonify({"error": "Account is disabled. Contact support."}), 403

    if not user.is_email_verified:
        return jsonify({
            "error": "Email not verified",
            "message": "Please verify your email before login."
        }), 403

    rider_status = None
    if user.role == "rider":
        rider_status = (
            user.rider_profile.verification_status
            if user.rider_profile else "profile_missing"
        )

    token = create_access_token(
        identity=str(user.id),
        additional_claims={"role": user.role}
    )

    return jsonify({
        "access_token": token,
        "user": {
            "id": user.id,
            "full_name": user.full_name,
            "email": user.email,
            "phone": user.phone,
            "role": user.role,
            "rider_status": rider_status
        }
    }), 200


@api_bp.get("/auth/me")
@jwt_required()
def get_current_user():
    user = db.session.get(User, int(get_jwt_identity()))

    if not user or not user.is_active:
        return jsonify({"error": "Account not found or disabled"}), 404

    rider_status = None
    is_online = False

    if user.role == "rider":
        if user.rider_profile:
            rider_status = user.rider_profile.verification_status
            is_online = bool(user.rider_profile.is_online)
        else:
            rider_status = "profile_missing"

    return jsonify({
        "id": user.id,
        "full_name": user.full_name,
        "email": user.email,
        "phone": user.phone,
        "role": user.role,
        "is_email_verified": bool(user.is_email_verified),
        "rider_status": rider_status,
        "is_online": is_online
    }), 200
