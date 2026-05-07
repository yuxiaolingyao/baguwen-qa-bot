package com.baguwen.bot.config;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class FailoverConfig {

    @Bean("failoverChatModel")
    @Primary
    public ChatModel failoverChatModel(@Qualifier("deepSeekChatModel") ChatModel primary,
                                        @Qualifier("bailianChatModel") ChatModel fallback) {
        return new FailoverChatModel(primary, fallback);
    }
}
