package com.baguwen.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 启动时自动建表/加列。isNewColumn 确保旧数据仅首次迁移时清除一次。
@Component
public class SchemaMigration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public SchemaMigration(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public void run(String... args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_messages ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "memory_id VARCHAR(128) NOT NULL, "
                + "role VARCHAR(8) NOT NULL, "
                + "content TEXT NOT NULL, "
                + "seq INT NOT NULL, "
                + "tool_data TEXT, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "INDEX idx_memory_seq (memory_id, seq)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        boolean isNewColumn = false;
        try {
            jdbc.execute("ALTER TABLE chat_messages ADD COLUMN tool_data TEXT "
                    + "COMMENT 'JSON: toolId/toolName for TOOL, toolRequests for AI'");
            isNewColumn = true;
            log.info("Schema 迁移: 添加 tool_data 列");
        } catch (Exception e) {
            log.debug("tool_data 列已存在，跳过");
        }

        if (isNewColumn) {
            try {
                int deleted = jdbc.update("DELETE FROM chat_messages");
                log.info("Schema 迁移: 清除 {} 条旧格式记录", deleted);
            } catch (Exception ex) {
                log.debug("清除旧记录跳过: {}", ex.getMessage());
            }
            try {
                redis.delete(redis.keys("chat:memory:*"));
                log.info("Schema 迁移: 清除 Redis 缓存");
            } catch (Exception ex) {
                log.debug("清除 Redis 缓存跳过: {}", ex.getMessage());
            }
        }
    }
}
