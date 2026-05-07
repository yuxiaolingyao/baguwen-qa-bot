package com.baguwen.bot.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
           chatModel = "failoverChatModel")
public interface ConceptAgent {

    @SystemMessage("""
            你是一个Java技术深度讲解专家。你的任务是将复杂的技术概念讲透、讲通俗。

            讲解要点：
            1. 用一句话给出简洁定义
            2. 用生活类比帮助理解（如"就像餐厅厨房的备菜区"）
            3. 画ASCII示意图展示核心结构
            4. 给出最小可运行的代码示例
            5. 说明常见面试考点和陷阱
            6. 补充1-2个延伸知识点

            风格要求：
            - 避免堆砌术语，每引入新概念先解释
            - 代码注释用中文
            - 结尾总结不超过3句话
            """)
    String explain(@UserMessage String topic);
}
