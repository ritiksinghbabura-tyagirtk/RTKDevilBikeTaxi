from datetime import datetime, timedelta, timezone
from flask import current_app, url_for
from flask_mail import Message
from app.extensions import db, mail
from app.models import EmailVerificationToken

def send_verification_email(user):
    token_str = EmailVerificationToken.generate_token()
    expires_at = datetime.now(timezone.utc) + timedelta(hours=24)

    token_obj = EmailVerificationToken(
        user_id=user.id,
        token=token_str,
        expires_at=expires_at
    )
    db.session.add(token_obj)
    db.session.commit()

    verification_link = f"{current_app.config['FRONTEND_URL']}/api/v1/auth/verify-email?token={token_str}"

    msg = Message(
        subject="RTKDevilBikeTaxi - Verify Your Email Address",
        recipients=[user.email]
    )
    msg.body = f"Hello {user.full_name},\n\nPlease verify your email for RTKDevilBikeTaxi by clicking this link:\n{verification_link}\n\nThis link will expire in 24 hours."
    msg.html = f"""
    <h3>Welcome to RTKDevilBikeTaxi, {user.full_name}!</h3>
    <p>Please click the button below to verify your account:</p>
    <a href="{verification_link}" style="background-color: #e63946; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; display: inline-block;">Verify Email</a>
    <p><br>Or copy this link into your browser: <br>{verification_link}</p>
    """
    
    try:
        mail.send(msg)
        return True, "Email sent successfully"
    except Exception as e:
        return False, str(e)
