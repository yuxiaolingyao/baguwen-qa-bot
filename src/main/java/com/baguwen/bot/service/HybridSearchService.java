package com.baguwen.bot.service;

import com.baguwen.bot.store.TextIndexStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final TextIndexStore textIndexStore;

    @Value("${knowledge.top-k:5}")
    private int topK;

    @Value("${knowledge.min-score:0.0}")
    private double minScore;

    public HybridSearchService(EmbeddingModel embeddingModel,
                               EmbeddingStore<TextSegment> embeddingStore,
                               TextIndexStore textIndexStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.textIndexStore = textIndexStore;
    }

    public void addSegment(TextSegment segment, Embedding embedding) {
        embeddingStore.add(embedding, segment);
        textIndexStore.addAll(List.of(segment));
    }

    public void addSegments(List<TextSegment> segments, List<Embedding> embeddings) {
        embeddingStore.addAll(embeddings, segments);
        textIndexStore.addAll(segments);
        log.debug("已入库 {} 个片段，文本索引总量: {}", segments.size(), textIndexStore.size());
    }

    public String searchAndFormat(String query) {
        List<TextSegment> results = search(query);
        if (results.isEmpty()) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            TextSegment seg = results.get(i);
            String filename = seg.metadata().getString("filename");
            String category = seg.metadata().getString("category");
            context.append("[来源: ").append(filename != null ? filename : "unknown");
            if (category != null) {
                context.append(" > ").append(category);
            }
            context.append("]\n").append(seg.text()).append("\n\n");
        }
        log.debug("检索结果 [{}] → {} 个片段", query, results.size());
        return context.toString();
    }

    public int getIndexSize() {
        return textIndexStore.size();
    }

    private List<TextSegment> search(String query) {
        if (embeddingStore instanceof PgVectorEmbeddingStore) {
            return pgVectorSearch(query);
        }
        return inMemoryHybridSearch(query);
    }

    private List<TextSegment> pgVectorSearch(String query) {
        try {
            Response<Embedding> queryEmbedding = embeddingModel.embed(query);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding.content())
                    .query(query)
                    .maxResults(topK)
                    .minScore(minScore)
                    .build();
            List<TextSegment> results = new ArrayList<>();
            for (dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> match
                    : embeddingStore.search(request).matches()) {
                results.add(match.embedded());
            }
            return results;
        } catch (Exception e) {
            log.debug("PgVector 检索异常: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<TextSegment> inMemoryHybridSearch(String query) {
        List<TextSegment> vectorResults = vectorSearch(query);
        List<TextSegment> keywordResults = keywordSearch(query);

        if (keywordResults.isEmpty()) {
            return vectorResults;
        }
        if (vectorResults.isEmpty()) {
            return keywordResults;
        }

        return rrfMerge(vectorResults, keywordResults, topK);
    }

    private List<TextSegment> vectorSearch(String query) {
        try {
            Response<Embedding> queryEmbedding = embeddingModel.embed(query);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding.content())
                    .maxResults(topK * 2)
                    .minScore(minScore)
                    .build();
            List<TextSegment> results = new ArrayList<>();
            for (dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> match
                    : embeddingStore.search(request).matches()) {
                results.add(match.embedded());
            }
            return results;
        } catch (Exception e) {
            log.debug("向量检索异常: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<TextSegment> keywordSearch(String query) {
        return textIndexStore.keywordSearch(query, topK * 2);
    }

    // RRF (Reciprocal Rank Fusion): 对两个排序列表加权合并，1/(k+rank+1)。
    // 去重键用 length_hashCode 组合，避免首 80 字符碰撞。
    private List<TextSegment> rrfMerge(List<TextSegment> a, List<TextSegment> b, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, TextSegment> lookup = new LinkedHashMap<>();

        addRRFScores(scores, lookup, a, RRF_K);
        addRRFScores(scores, lookup, b, RRF_K);

        List<TextSegment> merged = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(k)
                .forEachOrdered(new java.util.function.Consumer<Map.Entry<String, Double>>() {
                    @Override
                    public void accept(Map.Entry<String, Double> entry) {
                        merged.add(lookup.get(entry.getKey()));
                    }
                });

        return merged;
    }

    private void addRRFScores(Map<String, Double> scores, Map<String, TextSegment> lookup,
                              List<TextSegment> list, int k) {
        for (int i = 0; i < list.size(); i++) {
            TextSegment seg = list.get(i);
            String key = seg.text().length() + "_" + seg.text().hashCode();
            if (!lookup.containsKey(key)) {
                lookup.put(key, seg);
            }
            double rrf = 1.0 / (k + i + 1);
            scores.merge(key, rrf, Double::sum);
        }
    }
}
