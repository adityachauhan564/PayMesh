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
CREATE TABLE `wallet_transactions` (
  `amount` decimal(19,2) NOT NULL,
  `payment_initiation_date` datetime(6) DEFAULT NULL,
  `payment_updation_date` datetime(6) DEFAULT NULL,
  `receiver_id` bigint DEFAULT NULL,
  `sender_id` bigint DEFAULT NULL,
  `bill_account` varchar(255) DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `receiver_name` varchar(255) DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `sender_name` varchar(255) DEFAULT NULL,
  `transaction_id` varchar(255) NOT NULL,
  `bill_category` enum('BROADBAND','DTH','ELECTRICITY','GAS','MOBILE_RECHARGE','WATER') DEFAULT NULL,
  `status` enum('PENDING','REJECTED','SUCCESSFUL') NOT NULL,
  `type` enum('ADD_MONEY','BILL_PAYMENT','TRANSFER','WITHDRAW') NOT NULL,
  PRIMARY KEY (`transaction_id`),
  KEY `idx_txn_sender_id` (`sender_id`),
  KEY `idx_txn_receiver_id` (`receiver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


