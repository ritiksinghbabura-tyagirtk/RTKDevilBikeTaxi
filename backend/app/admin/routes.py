from flask import Blueprint, render_template

admin_bp = Blueprint("admin_web", __name__, url_prefix="/admin")

@admin_bp.get("/")
def admin_dashboard():
    return render_template("admin.html")
