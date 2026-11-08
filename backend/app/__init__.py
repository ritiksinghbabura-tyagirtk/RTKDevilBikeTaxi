from flask import Flask
from config import Config
from app.extensions import db, migrate, login_manager, jwt, mail
from app.models import User

def create_app(config_class=Config):
    flask_app = Flask(__name__, instance_relative_config=True)
    flask_app.config.from_object(config_class)

    db.init_app(flask_app)
    migrate.init_app(flask_app, db)
    login_manager.init_app(flask_app)
    jwt.init_app(flask_app)
    mail.init_app(flask_app)

    with flask_app.app_context():
        import app.models  # Create database tables
        db.create_all()

        # --- AUTO CREATE DEFAULT ADMIN IF NOT EXISTS ---
        admin_email = "admin@taxibike.com"
        existing_admin = User.query.filter_by(email=admin_email).first()
        if not existing_admin:
            admin_user = User(
                full_name="Super Admin",
                email=admin_email,
                phone="9999999999",
                role="admin",
                is_email_verified=True
            )
            admin_user.set_password("AdminPass123")
            db.session.add(admin_user)
            db.session.commit()
            print(">>> Default Admin Created: admin@taxibike.com / AdminPass123 <<<")

    from app.api import api_bp
    from app.admin.routes import admin_bp
    
    flask_app.register_blueprint(api_bp)
    flask_app.register_blueprint(admin_bp)

    @flask_app.get("/")
    def index():
        return {
            "name": "RTKDevilBikeTaxi",
            "message": "Backend is running",
            "admin_panel": "/admin/",
            "health": "/api/v1/health"
        }

    return flask_app
