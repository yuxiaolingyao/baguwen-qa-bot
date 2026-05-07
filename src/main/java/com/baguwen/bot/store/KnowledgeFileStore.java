package com.baguwen.bot.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class KnowledgeFileStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFileStore.class);
    private static final File LEARNED_FILE = new File("data/knowledge-learned.jsonl");

    private final ObjectMapper objectMapper;
    private final TextIndexStore textIndexStore;

    public KnowledgeFileStore(ObjectMapper objectMapper, TextIndexStore textIndexStore) {
        this.objectMapper = objectMapper;
        this.textIndexStore = textIndexStore;
    }

    /**
     * 从 JSON 文件加载条目到 EmbeddingStore 和 textIndex。
     * 文件格式与 {@code data/knowledge-base.json} 一致:
     * <pre>{@code
     * {"entries": [{"id": "uuid", "embedding": {"vector": [...]}, "embedded": {"text": "...", "metadata": {...}}}]}
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public int loadFromFile(EmbeddingStore<TextSegment> store, File file) {
        if (!file.exists()) {
            log.info("知识文件不存在，跳过加载: {}", file.getPath());
            return 0;
        }

        try {
            Map<String, Object> root = objectMapper.readValue(file,
                    new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> entries = (List<Map<String, Object>>) root.get("entries");
            if (entries == null || entries.isEmpty()) {
                return 0;
            }

            List<String> ids = new ArrayList<>(entries.size());
            List<Embedding> embeddings = new ArrayList<>(entries.size());
            List<TextSegment> segments = new ArrayList<>(entries.size());

            for (Map<String, Object> entry : entries) {
                String id = (String) entry.get("id");
                Map<String, Object> embeddingNode = (Map<String, Object>) entry.get("embedding");
                List<Double> vectorRaw = (List<Double>) embeddingNode.get("vector");
                float[] vector = new float[vectorRaw.size()];
                for (int i = 0; i < vectorRaw.size(); i++) {
                    vector[i] = vectorRaw.get(i).floatValue();
                }

                Map<String, Object> embedded = (Map<String, Object>) entry.get("embedded");
                String text = (String) embedded.get("text");
                Map<String, Object> rawMetadata = (Map<String, Object>) embedded.get("metadata");
                dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
                if (rawMetadata != null) {
                    for (Map.Entry<String, Object> kv : rawMetadata.entrySet()) {
                        metadata.put(kv.getKey(), String.valueOf(kv.getValue()));
                    }
                }

                ids.add(id);
                embeddings.add(new Embedding(vector));
                segments.add(TextSegment.from(text, metadata));
            }

            store.addAll(ids, embeddings, segments);
            textIndexStore.addAll(segments);
            log.info("已从文件加载 {} 条知识: {}", entries.size(), file.getPath());
            return entries.size();
        } catch (IOException e) {
            log.error("加载知识文件失败: {}", file.getPath(), e);
            return 0;
        }
    }

    // JSONL 格式 (每行一条 JSON): O(1) 追加 vs 旧 entries 数组格式的 O(n) 全量覆写。
    public void appendLearned(TextSegment segment, Embedding embedding) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", UUID.randomUUID().toString());

        Map<String, Object> embeddingNode = new LinkedHashMap<>();
        float[] vec = embedding.vector();
        List<Float> vectorList = new ArrayList<>(vec.length);
        for (float v : vec) {
            vectorList.add(v);
        }
        embeddingNode.put("vector", vectorList);
        entry.put("embedding", embeddingNode);

        Map<String, Object> embedded = new LinkedHashMap<>();
        embedded.put("text", segment.text());
        Map<String, Object> meta = new LinkedHashMap<>(segment.metadata().toMap());
        meta.putIfAbsent("source", "learned");
        embedded.put("metadata", meta);
        entry.put("embedded", embedded);

        try {
            LEARNED_FILE.getParentFile().mkdirs();
            try (PrintWriter writer = new PrintWriter(new FileWriter(LEARNED_FILE, true))) {
                writer.println(objectMapper.writeValueAsString(entry));
            }
            log.debug("知识已持久化: {}", segment.metadata().getString("filename"));
        } catch (IOException e) {
            log.error("持久化知识失败: {}", segment.metadata().getString("filename"), e);
        }
    }

    /**
     * 加载 learned JSONL 文件。
     */
    @SuppressWarnings("unchecked")
    public int loadLearned(EmbeddingStore<TextSegment> store) {
        if (!LEARNED_FILE.exists()) {
            return 0;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(LEARNED_FILE))) {
            List<String> ids = new ArrayList<>();
            List<Embedding> embeddings = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> entry = objectMapper.readValue(line,
                        new TypeReference<Map<String, Object>>() {});
                String id = (String) entry.get("id");
                Map<String, Object> embNode = (Map<String, Object>) entry.get("embedding");
                List<Double> vectorRaw = (List<Double>) embNode.get("vector");
                float[] vector = new float[vectorRaw.size()];
                for (int i = 0; i < vectorRaw.size(); i++) {
                    vector[i] = vectorRaw.get(i).floatValue();
                }

                Map<String, Object> embedded = (Map<String, Object>) entry.get("embedded");
                String text = (String) embedded.get("text");
                Map<String, Object> rawMetadata = (Map<String, Object>) embedded.get("metadata");
                dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
                if (rawMetadata != null) {
                    for (Map.Entry<String, Object> kv : rawMetadata.entrySet()) {
                        metadata.put(kv.getKey(), String.valueOf(kv.getValue()));
                    }
                }

                ids.add(id);
                embeddings.add(new Embedding(vector));
                segments.add(TextSegment.from(text, metadata));
            }

            if (!ids.isEmpty()) {
                store.addAll(ids, embeddings, segments);
                textIndexStore.addAll(segments);
                log.info("已从 learned 文件加载 {} 条知识", ids.size());
            }
            return ids.size();
        } catch (IOException e) {
            log.error("加载 learned 文件失败: {}", e.getMessage());
            return 0;
        }
    }

    public File getLearnedFile() {
        return LEARNED_FILE;
    }
}
