package com.baguwen.bot.service;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final CodeReviewAgent codeReviewAgent;
    private final ConceptAgent conceptAgent;

    public AgentTools(CodeReviewAgent codeReviewAgent, ConceptAgent conceptAgent) {
        this.codeReviewAgent = codeReviewAgent;
        this.conceptAgent = conceptAgent;
    }

    @Tool("委托代码审查专家对用户提交的代码进行详细审查。传入代码内容，返回详细的代码审查报告。当用户发送代码片段要求分析时使用。")
    public String delegateCodeReview(String code) {
        log.debug("Agent委托: CodeReview → 代码长度 {}", code.length());
        return codeReviewAgent.review(code);
    }

    @Tool("委托技术概念讲解专家深入浅出地讲解一个技术概念。传入概念名称，返回深度讲解内容。当用户要求深入理解某个技术概念时使用。")
    public String delegateConceptExplain(String topic) {
        log.debug("Agent委托: Concept → {}", topic);
        return conceptAgent.explain(topic);
    }
}
