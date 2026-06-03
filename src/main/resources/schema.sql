-- Chat Memory 表
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- RAG 知识库
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO knowledge_bases (id, name, description)
VALUES ('default', '默认知识库', '系统默认知识库');

-- RAG 知识库文档清单
CREATE TABLE IF NOT EXISTS rag_documents (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL DEFAULT 'default',
    content TEXT NOT NULL,
    source VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_knowledge_base_id (knowledge_base_id),
    INDEX idx_source (source),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
