import math
from flask import request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity

from app.api import api_bp
from app.extensions import db
from app.models import User, RiderProfile, VehicleCategory, Ride, RideMessage


def current_user():
    try:
        return db.session.get(User, int(get_jwt_identity()))
    except (TypeError, ValueError):
        return None


def valid_coordinates(data, names):
    try:
        values = [float(data[name]) for name in names]
    except (KeyError, TypeError, ValueError):
        return None

    if not all(math.isfinite(v) for v in values):
        return None

    lat1, lng1, lat2, lng2 = values
    if not (-90 <= lat1 <= 90 and -90 <= lat2 <= 90):
        return None
    if not (-180 <= lng1 <= 180 and -180 <= lng2 <= 180):
        return None

    return values


def haversine_distance(lat1, lon1, lat2, lon2):
    radius = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)

    a = (
        math.sin(dlat / 2) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(dlon / 2) ** 2
    )
    a = min(1.0, max(0.0, a))
    return round(radius * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a)), 2)


def ride_json(ride):
    return {
        "ride_id": ride.id,
        "customer_id": ride.customer_id,
        "rider_id": ride.rider_id,
        "vehicle_category_id": ride.vehicle_category_id,
        "pickup_lat": ride.pickup_lat,
        "pickup_lng": ride.pickup_lng,
        "drop_lat": ride.drop_lat,
        "drop_lng": ride.drop_lng,
        "distance_km": ride.distance_km,
        "total_fare": ride.total_fare,
        "surge_extra": ride.surge_extra_amount or 0,
        "status": ride.status,
        "created_at": ride.created_at.isoformat() if ride.created_at else None
    }


def can_access_ride(user, ride):
    return (
        user and ride and (
            user.role == "admin"
            or ride.customer_id == user.id
            or ride.rider_id == user.id
        )
    )


@api_bp.get("/rides/categories")
def get_categories():
    categories = VehicleCategory.query.order_by(VehicleCategory.id).all()

    if not categories:
        defaults = [
            VehicleCategory(name="Bike", base_fare=25.0, per_km_rate=10.0),
            VehicleCategory(name="Auto", base_fare=40.0, per_km_rate=15.0),
            VehicleCategory(name="Cab", base_fare=80.0, per_km_rate=22.0)
        ]
        db.session.add_all(defaults)
        db.session.commit()
        categories = VehicleCategory.query.order_by(VehicleCategory.id).all()

    return jsonify([{
        "id": c.id,
        "name": c.name,
        "base_fare": c.base_fare,
        "per_km_rate": c.per_km_rate,
        "icon_url": c.icon_url
    } for c in categories]), 200


@api_bp.post("/rides/estimate")
def estimate_fare():
    data = request.get_json(silent=True) or {}
    coords = valid_coordinates(
        data, ["pickup_lat", "pickup_lng", "drop_lat", "drop_lng"]
    )

    if not coords:
        return jsonify({"error": "Valid pickup and drop coordinates are required"}), 400

    distance = max(0.5, haversine_distance(*coords))
    categories = VehicleCategory.query.order_by(VehicleCategory.id).all()

    if not categories:
        return jsonify({"error": "No vehicle categories configured"}), 503

    estimates = [{
        "category_id": c.id,
        "category_name": c.name,
        "distance_km": distance,
        "estimated_fare": round(c.base_fare + distance * c.per_km_rate, 2)
    } for c in categories]

    return jsonify({"distance_km": distance, "estimates": estimates}), 200


@api_bp.post("/admin/categories")
@jwt_required()
def add_or_update_category():
    user = current_user()
    if not user or user.role != "admin":
        return jsonify({"error": "Admin access required"}), 403

    data = request.get_json(silent=True) or {}
    name = data.get("name")
    try:
        name = str(name).strip()
        base = float(data.get("base_fare"))
        rate = float(data.get("per_km_rate"))
    except (TypeError, ValueError):
        return jsonify({"error": "Valid name, base_fare and per_km_rate are required"}), 400

    if not name or len(name) > 50 or not math.isfinite(base) or not math.isfinite(rate):
        return jsonify({"error": "Invalid category values"}), 400
    if base < 0 or rate < 0:
        return jsonify({"error": "Fare values cannot be negative"}), 400

    category = VehicleCategory.query.filter_by(name=name).first()
    if category is None:
        category = VehicleCategory(name=name, base_fare=base, per_km_rate=rate)
        db.session.add(category)
    else:
        category.base_fare = base
        category.per_km_rate = rate

    db.session.commit()
    return jsonify({"message": "Vehicle category saved", "id": category.id}), 200


@api_bp.post("/rides/request")
@jwt_required()
def request_ride():
    user = current_user()
    if not user or not user.is_active or user.role != "customer":
        return jsonify({"error": "An active customer account is required"}), 403

    data = request.get_json(silent=True) or {}
    coords = valid_coordinates(
        data, ["pickup_lat", "pickup_lng", "drop_lat", "drop_lng"]
    )
    if not coords:
        return jsonify({"error": "Valid pickup and drop coordinates are required"}), 400

    try:
        category_id = int(data.get("vehicle_category_id"))
    except (TypeError, ValueError):
        return jsonify({"error": "A valid vehicle category is required"}), 400

    category = db.session.get(VehicleCategory, category_id)
    if not category:
        return jsonify({"error": "Vehicle category not found"}), 404

    distance = max(0.5, haversine_distance(*coords))
    fare = round(category.base_fare + distance * category.per_km_rate, 2)

    ride = Ride(
        customer_id=user.id,
        vehicle_category_id=category.id,
        pickup_lat=coords[0],
        pickup_lng=coords[1],
        drop_lat=coords[2],
        drop_lng=coords[3],
        distance_km=distance,
        base_fare_amount=fare,
        surge_extra_amount=0.0,
        total_fare=fare,
        status="searching"
    )

    db.session.add(ride)
    db.session.commit()

    return jsonify({
        "message": "Ride requested. Searching for a nearby rider.",
        "ride_id": ride.id,
        "total_fare": ride.total_fare,
        "distance_km": ride.distance_km,
        "status": ride.status
    }), 201


@api_bp.get("/rider/me")
@jwt_required()
def rider_me():
    user = current_user()
    if not user or user.role != "rider" or not user.rider_profile:
        return jsonify({"error": "Rider account not found"}), 403

    profile = user.rider_profile
    return jsonify({
        "rider_id": user.id,
        "full_name": user.full_name,
        "phone": user.phone,
        "vehicle_number": profile.vehicle_number,
        "verification_status": profile.verification_status,
        "is_online": bool(profile.is_online),
        "lat": profile.current_lat,
        "lng": profile.current_lng
    }), 200


@api_bp.post("/rider/location")
@jwt_required()
def update_rider_location():
    user = current_user()
    if not user or user.role != "rider" or not user.rider_profile:
        return jsonify({"error": "Rider account required"}), 403

    profile = user.rider_profile
    data = request.get_json(silent=True) or {}

    if profile.verification_status != "approved":
        profile.is_online = False
        db.session.commit()
        return jsonify({
            "error": "Rider approval is pending or rejected",
            "verification_status": profile.verification_status,
            "is_online": False
        }), 403

    online = data.get("is_online")
    if not isinstance(online, bool):
        return jsonify({"error": "is_online must be true or false"}), 400

    # Going offline must work even when GPS is disabled or unavailable.
    if not online:
        profile.is_online = False
        db.session.commit()
        return jsonify({
            "status": "success",
            "message": "Rider is now offline",
            "is_online": False
        }), 200

    try:
        lat = float(data["lat"])
        lng = float(data["lng"])
    except (KeyError, TypeError, ValueError):
        return jsonify({"error": "Valid GPS latitude and longitude are required"}), 400

    if not math.isfinite(lat) or not math.isfinite(lng):
        return jsonify({"error": "Invalid GPS coordinates"}), 400
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return jsonify({"error": "GPS coordinates are out of range"}), 400

    profile.current_lat = lat
    profile.current_lng = lng
    profile.is_online = True
    db.session.commit()

    return jsonify({
        "status": "success",
        "message": "Rider location updated",
        "is_online": profile.is_online
    }), 200


@api_bp.get("/rider/rides/nearby")
@jwt_required()
def nearby_rides():
    user = current_user()
    if not user or user.role != "rider" or not user.rider_profile:
        return jsonify({"error": "Rider account required"}), 403

    profile = user.rider_profile
    if profile.verification_status != "approved":
        return jsonify({"error": "Admin approval required"}), 403
    if not profile.is_online or profile.current_lat is None or profile.current_lng is None:
        return jsonify({"error": "Go online and share GPS location first"}), 409

    try:
        radius = float(request.args.get("radius_km", 15))
    except ValueError:
        radius = 15
    radius = min(50, max(1, radius))

    rides = Ride.query.filter_by(status="searching").order_by(Ride.created_at.asc()).limit(100).all()
    results = []

    for ride in rides:
        distance = haversine_distance(
            profile.current_lat, profile.current_lng,
            ride.pickup_lat, ride.pickup_lng
        )
        if distance <= radius:
            item = ride_json(ride)
            item["pickup_distance_km"] = distance
            results.append(item)

    results.sort(key=lambda item: item["pickup_distance_km"])
    return jsonify(results), 200


@api_bp.post("/rides/<int:ride_id>/respond")
@jwt_required()
def rider_respond_ride(ride_id):
    user = current_user()
    if not user or user.role != "rider" or not user.rider_profile:
        return jsonify({"error": "Rider account required"}), 403

    profile = user.rider_profile
    if profile.verification_status != "approved":
        return jsonify({"error": "Admin approval required"}), 403
    if not profile.is_online:
        return jsonify({"error": "Go online before accepting rides"}), 409

    ride = db.session.get(Ride, ride_id)
    if not ride:
        return jsonify({"error": "Ride not found"}), 404

    data = request.get_json(silent=True) or {}
    action = data.get("action")

    if action == "reject":
        return jsonify({"message": "Ride rejected", "ride_id": ride.id}), 200

    if action != "accept":
        return jsonify({"error": "Action must be accept or reject"}), 400

    if ride.status != "searching" or ride.rider_id is not None:
        return jsonify({"error": "Ride is no longer available"}), 409

    # Re-check immediately before committing to reduce simultaneous acceptance.
    ride.rider_id = user.id
    ride.status = "accepted"
    try:
        db.session.commit()
    except Exception:
        db.session.rollback()
        return jsonify({"error": "Ride could not be accepted; refresh and retry"}), 409

    return jsonify({
        "message": "Ride accepted successfully",
        "ride_id": ride.id,
        "status": ride.status
    }), 200


@api_bp.post("/rides/<int:ride_id>/status")
@jwt_required()
def update_ride_status(ride_id):
    user = current_user()
    ride = db.session.get(Ride, ride_id)

    if not ride:
        return jsonify({"error": "Ride not found"}), 404
    if not user or not can_access_ride(user, ride):
        return jsonify({"error": "You cannot access this ride"}), 403

    data = request.get_json(silent=True) or {}
    action = data.get("status")

    if user.role == "customer":
        if ride.customer_id != user.id:
            return jsonify({"error": "Only the ride customer can cancel"}), 403
        if action != "cancel":
            return jsonify({"error": "Customer can only cancel a ride"}), 400
        if ride.status not in ("searching", "accepted", "arrived"):
            return jsonify({"error": "Ride can no longer be cancelled"}), 409
        ride.status = "cancelled"

    elif user.role == "rider":
        if ride.rider_id != user.id:
            return jsonify({"error": "This ride is not assigned to you"}), 403
        if user.rider_profile.verification_status != "approved":
            return jsonify({"error": "Rider approval required"}), 403

        transitions = {
            "arrived": ("accepted",),
            "started": ("arrived",),
            "completed": ("started",)
        }
        if action not in transitions or ride.status not in transitions[action]:
            return jsonify({"error": "Invalid ride status transition"}), 409
        ride.status = action

    elif user.role == "admin":
        if action not in ("cancelled",):
            return jsonify({"error": "Admin can only cancel through this endpoint"}), 400
        ride.status = "cancelled"
    else:
        return jsonify({"error": "Not authorized"}), 403

    if ride.status == "cancelled" and ride.rider_id:
        rider = db.session.get(User, ride.rider_id)
        if rider and rider.rider_profile and ride.status == "cancelled":
            if action == "cancel" and user.role == "customer" and ride.status == "cancelled":
                # Keep the assigned rider for the audit trail.
                pass

    db.session.commit()
    return jsonify({
        "message": "Ride status updated",
        "ride_id": ride.id,
        "status": ride.status
    }), 200


@api_bp.get("/rides/history")
@jwt_required()
def ride_history():
    user = current_user()
    if not user:
        return jsonify({"error": "Account not found"}), 404

    if user.role == "customer":
        query = Ride.query.filter_by(customer_id=user.id)
    elif user.role == "rider":
        query = Ride.query.filter_by(rider_id=user.id)
    elif user.role == "admin":
        query = Ride.query
    else:
        return jsonify({"error": "Not authorized"}), 403

    rides = query.order_by(Ride.created_at.desc()).limit(100).all()
    return jsonify([ride_json(ride) for ride in rides]), 200


@api_bp.post("/rides/<int:ride_id>/add-surge")
@jwt_required()
def add_surge(ride_id):
    user = current_user()
    ride = db.session.get(Ride, ride_id)

    if not ride:
        return jsonify({"error": "Ride not found"}), 404
    if not user or (user.role != "admin" and ride.customer_id != user.id):
        return jsonify({"error": "Not authorized to change this fare"}), 403

    data = request.get_json(silent=True) or {}
    try:
        extra = float(data.get("extra_amount", 0))
    except (TypeError, ValueError):
        return jsonify({"error": "Invalid extra amount"}), 400

    if not math.isfinite(extra) or extra < 0 or extra > 5000:
        return jsonify({"error": "Extra amount must be between 0 and 5000"}), 400
    if ride.status not in ("searching", "accepted"):
        return jsonify({"error": "Fare cannot be changed at this stage"}), 409

    ride.surge_extra_amount = (ride.surge_extra_amount or 0) + extra
    ride.total_fare = round(ride.base_fare_amount + ride.surge_extra_amount, 2)
    db.session.commit()

    return jsonify({
        "message": "Extra amount added",
        "ride_id": ride.id,
        "new_total_fare": ride.total_fare,
        "surge_extra": ride.surge_extra_amount
    }), 200


@api_bp.post("/rides/<int:ride_id>/chat")
@jwt_required()
def send_ride_message(ride_id):
    user = current_user()
    ride = db.session.get(Ride, ride_id)

    if not ride:
        return jsonify({"error": "Ride not found"}), 404
    if not can_access_ride(user, ride):
        return jsonify({"error": "You cannot access this ride chat"}), 403

    data = request.get_json(silent=True) or {}
    message = data.get("message")
    if not isinstance(message, str) or not message.strip():
        return jsonify({"error": "Message cannot be empty"}), 400
    if len(message) > 2000:
        return jsonify({"error": "Message is too long"}), 400

    msg = RideMessage(
        ride_id=ride.id,
        sender_id=user.id,
        message=message.strip()
    )
    db.session.add(msg)
    db.session.commit()

    return jsonify({
        "status": "success",
        "sender_id": msg.sender_id,
        "message": msg.message,
        "timestamp": msg.timestamp.isoformat()
    }), 201


@api_bp.get("/rides/<int:ride_id>/chat")
@jwt_required()
def get_ride_messages(ride_id):
    user = current_user()
    ride = db.session.get(Ride, ride_id)

    if not ride:
        return jsonify({"error": "Ride not found"}), 404
    if not can_access_ride(user, ride):
        return jsonify({"error": "You cannot access this ride chat"}), 403

    messages = RideMessage.query.filter_by(ride_id=ride.id).order_by(
        RideMessage.timestamp.asc()
    ).limit(500).all()

    return jsonify([{
        "sender_id": m.sender_id,
        "message": m.message,
        "timestamp": m.timestamp.isoformat() if m.timestamp else None
    } for m in messages]), 200


@api_bp.get("/admin/riders/pending")
@jwt_required()
def get_pending_riders():
    user = current_user()
    if not user or user.role != "admin":
        return jsonify({"error": "Admin access required"}), 403

    profiles = RiderProfile.query.filter_by(
        verification_status="pending"
    ).all()

    results = []
    for profile in profiles:
        rider = db.session.get(User, profile.user_id)
        if rider:
            results.append({
                "rider_id": rider.id,
                "full_name": rider.full_name,
                "email": rider.email,
                "phone": rider.phone,
                "vehicle_number": profile.vehicle_number,
                "driving_license": profile.driving_license,
                "verification_status": profile.verification_status
            })

    return jsonify(results), 200


@api_bp.post("/admin/riders/<int:rider_user_id>/verify")
@jwt_required()
def verify_rider(rider_user_id):
    admin = current_user()
    if not admin or admin.role != "admin":
        return jsonify({"error": "Admin access required"}), 403

    rider = db.session.get(User, rider_user_id)
    if not rider or rider.role != "rider" or not rider.rider_profile:
        return jsonify({"error": "Rider profile not found"}), 404

    data = request.get_json(silent=True) or {}
    status = data.get("status")

    if status not in ("approved", "rejected"):
        return jsonify({"error": "Status must be approved or rejected"}), 400

    rider.rider_profile.verification_status = status
    if status != "approved":
        rider.rider_profile.is_online = False

    db.session.commit()
    return jsonify({
        "message": "Rider verification updated",
        "rider_id": rider.id,
        "verification_status": status
    }), 200


@api_bp.get("/rides/<int:ride_id>")
@jwt_required()
def get_ride_details(ride_id):
    user = current_user()
    ride = db.session.get(Ride, ride_id)

    if not ride:
        return jsonify({"error": "Ride not found"}), 404
    if not can_access_ride(user, ride):
        return jsonify({"error": "You cannot access this ride"}), 403

    result = ride_json(ride)
    rider_info = None

    if ride.rider_id:
        rider = db.session.get(User, ride.rider_id)
        if rider and rider.rider_profile:
            rider_info = {
                "id": rider.id,
                "name": rider.full_name,
                "phone": rider.phone,
                "vehicle_number": rider.rider_profile.vehicle_number,
                "lat": rider.rider_profile.current_lat,
                "lng": rider.rider_profile.current_lng
            }

    result["rider"] = rider_info
    return jsonify(result), 200
