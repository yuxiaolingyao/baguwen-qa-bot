package com.baguwen.bot.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConversationCompressor {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompressor.class);

    private final ChatModel model;

    public ConversationCompressor(@Qualifier("failoverChatModel") ChatModel model) {
        this.model = model;
    }

    public String compress(String previousSummary, List<ChatMessage> messages) {
        StringBuilder dialog = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                dialog.append("用户: ").append(((UserMessage) msg).singleText()).append("\n");
            } else if (msg instanceof AiMessage) {
                dialog.append("AI: ").append(((AiMessage) msg).text()).append("\n");
            }
        }

        String prompt;
        if (previousSummary != null && !previousSummary.isEmpty()) {
            prompt = "你是对话摘要工具。请把\"之前的摘要\"和\"新增的对话\"合并为一段新的简洁摘要（300字以内），"
                    + "保留关键知识点、用户的薄弱点和讨论脉络。\n\n"
                    + "之前的摘要: " + previousSummary + "\n\n"
                    + "新增的对话:\n" + dialog + "\n\n"
                    + "新摘要:";
        } else {
            prompt = "你是对话摘要工具。请把以下对话压缩为一段简洁摘要（300字以内），"
                    + "保留关键知识点和讨论脉络。\n\n"
                    + dialog + "\n\n"
                    + "摘要:";
        }

        String result = model.chat(prompt);
        log.debug("压缩完成，原文 {} 条 → 摘要 {} 字", messages.size(), result.length());
        return result;
    }
}
