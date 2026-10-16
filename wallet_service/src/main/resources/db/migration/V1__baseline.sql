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
CREATE TABLE `wallet` (
  `balance` decimal(19,2) NOT NULL,
  `daily_limit` decimal(19,2) NOT NULL,
  `daily_transaction_limit` int NOT NULL,
  `spent_today` decimal(19,2) NOT NULL,
  `txn_count_today` int NOT NULL,
  `usage_date` date DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `wallet_creation_time` datetime(6) DEFAULT NULL,
  `wallet_id` bigint NOT NULL AUTO_INCREMENT,
  `wallet_updation_time` datetime(6) DEFAULT NULL,
  `owner_email` varchar(255) DEFAULT NULL,
  `owner_name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`wallet_id`),
  UNIQUE KEY `uk_wallet_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `wallet_processed_transaction` (
  `from_name` varchar(255) DEFAULT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `to_name` varchar(255) DEFAULT NULL,
  `transaction_id` varchar(255) NOT NULL,
  `status` enum('PENDING','REJECTED','SUCCESSFUL') DEFAULT NULL,
  PRIMARY KEY (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


