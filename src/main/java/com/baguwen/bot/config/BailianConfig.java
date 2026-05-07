package com.baguwen.bot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class BailianConfig {

    @Value("${bailian.api-key}")
    private String apiKey;

    @Value("${bailian.base-url}")
    private String baseUrl;

    @Value("${bailian.model-name}")
    private String modelName;

    @Value("${bailian.temperature}")
    private Double temperature;

    @Value("${bailian.max-tokens}")
    private Integer maxTokens;

    @Bean
    public ChatModel bailianChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean("bailianVisionChatModel")
    public ChatModel bailianVisionChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName("qwen-vl-plus")
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
