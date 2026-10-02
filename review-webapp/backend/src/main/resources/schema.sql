CREATE TABLE IF NOT EXISTS users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL,
  CONSTRAINT ck_user_role CHECK (role IN ('ADMIN', 'REVIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS traffic_records (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_key VARCHAR(96) NOT NULL UNIQUE,
  source_row INT NOT NULL,
  model_version VARCHAR(64) NOT NULL,
  features_json JSON NOT NULL,
  predicted_label VARCHAR(16) NOT NULL,
  score DOUBLE NOT NULL,
  quantization_error DOUBLE NOT NULL,
  som_x INT NOT NULL,
  som_y INT NOT NULL,
  neuron_count INT NOT NULL,
  cluster_purity DOUBLE NOT NULL,
  reviewed_label VARCHAR(16) NULL,
  note VARCHAR(1000) NOT NULL DEFAULT '',
  reviewed_by BIGINT NULL,
  updated_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  locked_by BIGINT NULL,
  lock_token VARCHAR(36) NULL,
  locked_until DATETIME(6) NULL,
  CONSTRAINT fk_review_user FOREIGN KEY (reviewed_by) REFERENCES users(id),
  CONSTRAINT fk_lock_user FOREIGN KEY (locked_by) REFERENCES users(id),
  CONSTRAINT ck_predicted CHECK (predicted_label IN ('NORMAL', 'ATTACK')),
  CONSTRAINT ck_reviewed CHECK (reviewed_label IS NULL OR reviewed_label IN ('NORMAL', 'ATTACK')),
  CONSTRAINT ck_score CHECK (score >= 0 AND score <= 1),
  INDEX idx_queue (deleted_at, reviewed_label, score, id),
  INDEX idx_prediction (deleted_at, predicted_label, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
