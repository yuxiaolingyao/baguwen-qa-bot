package com.baguwen.bot.config;

import com.baguwen.bot.mapper.ChatMessageMapper;
import com.baguwen.bot.store.RedisMysqlChatMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemoryStore chatMemoryStore(StringRedisTemplate redis,
                                           ChatMessageMapper mapper,
                                           ObjectMapper objectMapper) {
        return new RedisMysqlChatMemoryStore(redis, mapper, objectMapper);
    }

    @Bean("baguwenMemoryProvider")
    public ChatMemoryProvider baguwenMemoryProvider(ChatMemoryStore chatMemoryStore,
                                                     MemoryManager memoryManager) {
        return new ChatMemoryProvider() {
            @Override
            public dev.langchain4j.memory.ChatMemory get(Object memoryId) {
                return memoryManager.getOrCreate(memoryId,
                        new MemoryManager.ChatMemoryFactory() {
                            @Override
                            public dev.langchain4j.memory.ChatMemory create(Object id) {
                                return MessageWindowChatMemory.builder()
                                        .id(id)
                                        .maxMessages(20)
                                        .chatMemoryStore(chatMemoryStore)
                                        .build();
                            }
                        });
            }
        };
    }
}
