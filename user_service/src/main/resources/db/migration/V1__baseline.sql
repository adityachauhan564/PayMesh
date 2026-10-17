-- Baseline: the schema Hibernate generated before Flyway (ddl-auto=update). Never edit; add V2__... instead.
CREATE TABLE `outbox_event` (
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `producer` varchar(64) NOT NULL,
  `message_key` varchar(255) NOT NULL,
  `topic` varchar(255) NOT NULL,
  `payload` mediumtext NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_producer_id` (`producer`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `wallet_user` (
  `failed_login_attempts` int NOT NULL,
  `created_date` datetime(6) DEFAULT NULL,
  `last_failed_login_at` datetime(6) DEFAULT NULL,
  `locked_until` datetime(6) DEFAULT NULL,
  `updated_date` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `password_hash` varchar(255) NOT NULL,
  `user_address` varchar(255) DEFAULT NULL,
  `user_email_id` varchar(255) NOT NULL,
  `user_full_name` varchar(255) NOT NULL,
  `user_mobile_no` varchar(255) NOT NULL,
  `user_pan` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_wallet_user_email` (`user_email_id`),
  UNIQUE KEY `uk_wallet_user_mobile` (`user_mobile_no`),
  UNIQUE KEY `uk_wallet_user_pan` (`user_pan`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


