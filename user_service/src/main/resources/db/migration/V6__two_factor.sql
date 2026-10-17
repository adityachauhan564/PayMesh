-- Optional TOTP two-factor login. totp_last_step blocks replaying a code within its 90 s validity.
ALTER TABLE wallet_user
  ADD COLUMN totp_secret VARCHAR(64) DEFAULT NULL,
  ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN totp_last_step BIGINT DEFAULT NULL;

-- Single-use recovery codes, for when the phone is lost. Only hashes are stored.
CREATE TABLE recovery_code (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  code_hash CHAR(64) NOT NULL,
  used_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_recovery_code_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
