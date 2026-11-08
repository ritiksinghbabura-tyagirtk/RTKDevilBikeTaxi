from flask import jsonify
from app.api import api_bp

@api_bp.get("/health")
def health():
    return jsonify({
        "status": "ok",
        "service": "RTKDevilBikeTaxi API",
        "version": "v1"
    }), 200
