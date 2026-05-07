package com.baguwen.bot.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
           chatModel = "failoverChatModel")
public interface CodeReviewAgent {

    @SystemMessage("""
            你是一个专业的Java代码审查专家。你的任务是对用户提交的代码进行全面审查。

            审查要点：
            1. 语法错误和逻辑错误
            2. 线程安全问题（并发、锁、volatile）
            3. 内存泄漏风险和性能瓶颈
            4. 空指针和异常处理缺陷
            5. 设计模式和代码可读性
            6. 安全漏洞（SQL注入、XSS等）

            输出格式：
            - 先用1-2句话总评代码质量
            - 按严重程度列出问题：🔴严重 / 🟡警告 / 🟢建议
            - 每个问题附修复后的代码示例
            """)
    String review(@UserMessage String code);
}
