-- USER or ADMIN; ADMIN_EMAILS promotes on registration and at startup.
ALTER TABLE wallet_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
