package com.baguwen.bot.service;

import com.baguwen.bot.config.MemoryManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BaguwenAgentServiceImpl implements BaguwenAgentService {

    private static final Logger log = LoggerFactory.getLogger(BaguwenAgentServiceImpl.class);

    private static final int MEMORY_NEAR_FULL = 18;
    private static final int COMPRESS_OLDEST_COUNT = 10;

    private final BaguwenAssistant assistant;
    private final MemoryManager memoryManager;
    private final ConversationCompressor compressor;

    public BaguwenAgentServiceImpl(BaguwenAssistant assistant,
                                   MemoryManager memoryManager,
                                   ConversationCompressor compressor) {
        this.assistant = assistant;
        this.memoryManager = memoryManager;
        this.compressor = compressor;
    }

    @Override
    public String chat(String userId, String message) {
        maybeCompress(userId);
        return assistant.chat(userId, buildEnhancedMessage(userId, message));
    }

    @Override
    public boolean isMemoryNearFull(String userId) {
        return memoryManager.getMessageCount(userId) >= MEMORY_NEAR_FULL;
    }

    @Override
    public void clearMemory(String userId) {
        memoryManager.clear(userId);
        log.info("用户 [{}] 已清空记忆", userId);
    }

    // ─── 压缩逻辑 ──────────────────────────────────────

    // >= 18 条消息触发压缩：取最旧 10 条 → LLM 生成摘要 → ChatMemory.set() 原子替换。
    // 防御性拷贝 memory.messages() 避免 clear() + add() 循环导致的内部引用共享问题。
    private void maybeCompress(String userId) {
        int count = memoryManager.getMessageCount(userId);
        if (count < MEMORY_NEAR_FULL) {
            return;
        }

        ChatMemory memory = memoryManager.get(userId);
        if (memory == null) {
            return;
        }

        List<ChatMessage> allMessages = new ArrayList<>(memory.messages());
        if (allMessages.size() < COMPRESS_OLDEST_COUNT + 4) {
            return;
        }

        int cutoff = Math.min(COMPRESS_OLDEST_COUNT, allMessages.size() - 8);
        List<ChatMessage> toCompress = allMessages.subList(0, cutoff);
        String previousSummary = memoryManager.getSummary(userId);
        String newSummary = compressor.compress(previousSummary, toCompress);
        memoryManager.setSummary(userId, newSummary);

        List<ChatMessage> remaining = new ArrayList<>(allMessages.subList(cutoff, allMessages.size()));
        if (!remaining.isEmpty()) {
            memory.set(remaining);
        }

        log.info("用户 [{}] 压缩完成: {} 条 → 摘要, 剩余 {} 条",
                userId, cutoff, remaining.size());
    }

    private String buildEnhancedMessage(String userId, String message) {
        StringBuilder enhanced = new StringBuilder();

        String summary = memoryManager.getSummary(userId);
        if (summary != null) {
            enhanced.append("[对话背景]\n").append(summary).append("\n\n");
        }

        enhanced.append(message);
        return enhanced.toString();
    }
}
