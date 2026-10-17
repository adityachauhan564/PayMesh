-- One-time unlock link sent with the lockout email (hash only; 30 minutes; a new lock replaces it).
ALTER TABLE wallet_user
  ADD COLUMN unlock_token_hash CHAR(64) DEFAULT NULL,
  ADD COLUMN unlock_expires_at DATETIME(6) DEFAULT NULL;
