package com.baguwen.bot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FailoverChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatModel.class);

    private final ChatModel primary;
    private final ChatModel fallback;

    public FailoverChatModel(ChatModel primary, ChatModel fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    // 主模型失败时切换到备用模型。仅捕获 RuntimeException，不做重试。
    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        try {
            return primary.chat(chatRequest);
        } catch (RuntimeException e) {
            log.warn("主模型失败，切换备用模型: {}", e.getMessage());
            return fallback.chat(chatRequest);
        }
    }

    @Override
    public String chat(String userMessage) {
        try {
            return primary.chat(userMessage);
        } catch (RuntimeException e) {
            log.warn("主模型失败，切换备用模型: {}", e.getMessage());
            return fallback.chat(userMessage);
        }
    }
}
