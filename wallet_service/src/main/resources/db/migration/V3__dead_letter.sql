-- Messages this service gave up on (<topic>.DLT), kept for inspection, replay or discard (see DeadLetterStore).
CREATE TABLE dead_letter (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dlt_topic VARCHAR(255) NOT NULL,
  dlt_partition INT NOT NULL,
  dlt_offset BIGINT NOT NULL,
  original_topic VARCHAR(255) NOT NULL,
  message_key VARCHAR(255) DEFAULT NULL,
  payload MEDIUMTEXT NOT NULL,
  exception_class VARCHAR(255) DEFAULT NULL,
  exception_message VARCHAR(1000) DEFAULT NULL,
  status ENUM('DISCARDED','NEW','REPLAYED') NOT NULL,
  created_at DATETIME(6) DEFAULT NULL,
  updated_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dead_letter_position (dlt_topic, dlt_partition, dlt_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
