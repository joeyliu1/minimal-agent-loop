-- Chat memory table: stores every message in every session
CREATE TABLE IF NOT EXISTS chat_messages (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(36)  NOT NULL,
    role        VARCHAR(20)  NOT NULL,  -- 'user' or 'assistant'
    content     TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_created  (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Chat sessions metadata
CREATE TABLE IF NOT EXISTS chat_sessions (
    session_id   VARCHAR(36) PRIMARY KEY,
    title        VARCHAR(200) NOT NULL DEFAULT '新对话',
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;