package com.baguwen.bot.config;

import dev.langchain4j.memory.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final long TTL_MILLIS = 30 * 60 * 1000; // 30 minutes

    private final Map<Object, ChatMemory> memoryCache = new ConcurrentHashMap<>();
    private final Map<Object, String> summaries = new ConcurrentHashMap<>();
    private final Map<Object, Long> lastAccessTimes = new ConcurrentHashMap<>();

    public ChatMemory getOrCreate(Object memoryId, ChatMemoryFactory factory) {
        lastAccessTimes.put(memoryId, System.currentTimeMillis());
        return memoryCache.computeIfAbsent(memoryId,
                new java.util.function.Function<Object, ChatMemory>() {
                    @Override
                    public ChatMemory apply(Object id) {
                        return factory.create(id);
                    }
                });
    }

    public ChatMemory get(Object memoryId) {
        lastAccessTimes.put(memoryId, System.currentTimeMillis());
        return memoryCache.get(memoryId);
    }

    public int getMessageCount(Object memoryId) {
        ChatMemory m = memoryCache.get(memoryId);
        return m == null ? 0 : m.messages().size();
    }

    public void clear(Object memoryId) {
        ChatMemory m = memoryCache.remove(memoryId);
        if (m != null) {
            m.clear();
        }
        summaries.remove(memoryId);
        lastAccessTimes.remove(memoryId);
    }

    public String getSummary(Object memoryId) {
        return summaries.get(memoryId);
    }

    public void setSummary(Object memoryId, String summary) {
        summaries.put(memoryId, summary);
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void evictStaleMemories() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Object, Long>> it = lastAccessTimes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Long> entry = it.next();
            if (now - entry.getValue() > TTL_MILLIS) {
                Object memoryId = entry.getKey();
                ChatMemory m = memoryCache.remove(memoryId);
                if (m != null) {
                    m.clear();
                }
                summaries.remove(memoryId);
                it.remove();
                log.debug("淘汰过期记忆 [{}]", memoryId);
            }
        }
    }

    @FunctionalInterface
    public interface ChatMemoryFactory {
        ChatMemory create(Object id);
    }
}
