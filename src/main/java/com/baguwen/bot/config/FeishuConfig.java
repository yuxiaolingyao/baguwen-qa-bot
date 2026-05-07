package com.baguwen.bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "feishu")
@Data
public class FeishuConfig {

    private String appId;
    private String appSecret;
    private String botName;
}
