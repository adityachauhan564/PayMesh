-- USER or ADMIN. ADMIN is granted out of band (this column, or ADMIN_USER_IDS by user id), never at self-registration.
ALTER TABLE wallet_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
