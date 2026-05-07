package com.baguwen.bot.service;

import com.baguwen.bot.store.KnowledgeFileStore;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);

    private final EmbeddingModel embeddingModel;
    private final HybridSearchService hybridSearch;
    private final KnowledgeFileStore knowledgeFileStore;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public KnowledgeTools(EmbeddingModel embeddingModel,
                          HybridSearchService hybridSearch,
                          KnowledgeFileStore knowledgeFileStore,
                          EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.hybridSearch = hybridSearch;
        this.knowledgeFileStore = knowledgeFileStore;
        this.embeddingStore = embeddingStore;
    }

    @Tool("搜索内部知识库，传入技术关键词或问题，返回相关参考资料片段。当用户提问时优先使用此工具查询。")
    public String searchKnowledge(String keyword) {
        log.debug("Tool调用 searchKnowledge: {}", keyword);
        String result = hybridSearch.searchAndFormat(keyword);
        if (result == null) {
            return "知识库中未找到相关资料。";
        }
        return result;
    }

    @Tool("将发现的新知识点写入知识库。当回答中涉及知识库未覆盖的重要技术内容时，调用此方法保存，供后续检索使用。")
    public String saveToKnowledge(String title, String content, String category) {
        log.info("Tool调用 saveToKnowledge: title={}, category={}", title, category);
        try {
            TextSegment segment = TextSegment.from(content,
                    dev.langchain4j.data.document.Metadata
                            .from("filename", title)
                            .put("category", category)
                            .put("source", "learned"));
            Response<Embedding> embedding = embeddingModel.embed(content);
            hybridSearch.addSegment(segment, embedding.content());
            if (embeddingStore instanceof InMemoryEmbeddingStore) {
                knowledgeFileStore.appendLearned(segment, embedding.content());
            }
            log.info("知识已保存: {} (分类: {})", title, category);
            return "已保存知识点: " + title;
        } catch (Exception e) {
            log.error("保存知识点失败: {}", title, e);
            return "保存失败: " + e.getMessage();
        }
    }
}
