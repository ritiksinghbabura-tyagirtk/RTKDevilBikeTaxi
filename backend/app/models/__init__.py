from datetime import datetime, timezone
import secrets
from werkzeug.security import generate_password_hash, check_password_hash
from app.extensions import db

class User(db.Model):
    __tablename__ = "users"

    id = db.Column(db.Integer, primary_key=True)
    full_name = db.Column(db.String(100), nullable=False)
    email = db.Column(db.String(120), unique=True, nullable=False, index=True)
    phone = db.Column(db.String(20), unique=True, nullable=False)
    password_hash = db.Column(db.String(255), nullable=False)
    role = db.Column(db.String(20), nullable=False, default="customer") # customer, rider, admin
    
    is_email_verified = db.Column(db.Boolean, default=False)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))

    rider_profile = db.relationship("RiderProfile", backref="user", uselist=False, cascade="all, delete-orphan")
    verification_tokens = db.relationship("EmailVerificationToken", backref="user", cascade="all, delete-orphan")

    def set_password(self, password):
        self.password_hash = generate_password_hash(password)

    def check_password(self, password):
        return check_password_hash(self.password_hash, password)

class RiderProfile(db.Model):
    __tablename__ = "rider_profiles"

    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=False)
    vehicle_number = db.Column(db.String(30), nullable=False)
    driving_license = db.Column(db.String(50), nullable=False)
    verification_status = db.Column(db.String(20), default="pending") # pending, approved, rejected
    is_online = db.Column(db.Boolean, default=False)
    current_lat = db.Column(db.Float, nullable=True)
    current_lng = db.Column(db.Float, nullable=True)

class EmailVerificationToken(db.Model):
    __tablename__ = "email_verification_tokens"

    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=False)
    token = db.Column(db.String(100), unique=True, nullable=False, index=True)
    expires_at = db.Column(db.DateTime, nullable=False)
    used = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))

    @staticmethod
    def generate_token():
        return secrets.token_urlsafe(32)

class VehicleCategory(db.Model):
    __tablename__ = "vehicle_categories"
    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(50), unique=True, nullable=False) # Bike, Auto, Cab
    base_fare = db.Column(db.Float, default=30.0) # Base starting amount
    per_km_rate = db.Column(db.Float, default=12.0) # Per KM charge
    icon_url = db.Column(db.String(255), nullable=True)

class Ride(db.Model):
    __tablename__ = "rides"
    id = db.Column(db.Integer, primary_key=True)
    customer_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=False)
    rider_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=True)
    vehicle_category_id = db.Column(db.Integer, db.ForeignKey("vehicle_categories.id"), nullable=False)
    
    pickup_lat = db.Column(db.Float, nullable=False)
    pickup_lng = db.Column(db.Float, nullable=False)
    drop_lat = db.Column(db.Float, nullable=False)
    drop_lng = db.Column(db.Float, nullable=False)
    
    distance_km = db.Column(db.Float, nullable=False)
    base_fare_amount = db.Column(db.Float, nullable=False)
    surge_extra_amount = db.Column(db.Float, default=0.0) # +20, +50, +100 surge
    total_fare = db.Column(db.Float, nullable=False)
    
    status = db.Column(db.String(30), default="searching") # searching, accepted, arrived, started, completed, cancelled
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))

class RideMessage(db.Model):
    __tablename__ = "ride_messages"
    id = db.Column(db.Integer, primary_key=True)
    ride_id = db.Column(db.Integer, db.ForeignKey("rides.id"), nullable=False)
    sender_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=False)
    message = db.Column(db.Text, nullable=False)
    timestamp = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))
