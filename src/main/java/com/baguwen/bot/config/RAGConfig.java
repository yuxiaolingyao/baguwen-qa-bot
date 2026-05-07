package com.baguwen.bot.config;

import com.baguwen.bot.service.HybridSearchService;
import com.baguwen.bot.store.KnowledgeFileStore;
import com.baguwen.bot.util.TextSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

@Configuration
public class RAGConfig {

    private static final Logger log = LoggerFactory.getLogger(RAGConfig.class);

    private static final String DEFAULT_DOC = "docs/Java 八股文.md";
    private static final java.io.File PREBUILT_JSON = new java.io.File("data/knowledge-base.json");

    @Value("${pgvector.datasource.jdbc-url}")
    private String pgJdbcUrl;

    @Value("${pgvector.datasource.username}")
    private String pgUsername;

    @Value("${pgvector.datasource.password}")
    private String pgPassword;

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15QuantizedEmbeddingModel();
    }

    // PgVector 连接失败时自动降级为 InMemoryEmbeddingStore，Windows 下无需安装 PostgreSQL。
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        try {
            DataSource pgDs = DataSourceBuilder.create()
                    .url(pgJdbcUrl)
                    .username(pgUsername)
                    .password(pgPassword)
                    .build();
            PgVectorEmbeddingStore store = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(pgDs)
                    .table("baguwen_kb")
                    .dimension(512)
                    .searchMode(PgVectorEmbeddingStore.SearchMode.HYBRID)
                    .rrfK(60)
                    .textSearchConfig("simple")
                    .createTable(true)
                    .dropTableFirst(false)
                    .build();
            log.info("PgVector 已连接，混合检索模式 (HYBRID)");
            return store;
        } catch (Exception e) {
            log.warn("PgVector 连接失败，降级为内存向量库: {}", e.getMessage());
            return new InMemoryEmbeddingStore<>();
        }
    }

    @Bean
    public CommandLineRunner loadDefaultDocs(EmbeddingStore<TextSegment> store,
                                              EmbeddingModel model,
                                              HybridSearchService hybridSearch,
                                              KnowledgeFileStore knowledgeFileStore) {
        return new CommandLineRunner() {
            @Override
            public void run(String... args) throws Exception {
                if (store instanceof PgVectorEmbeddingStore && storeHasData((PgVectorEmbeddingStore) store, model)) {
                    log.info("PgVector 已有数据，跳过默认文档加载");
                    return;
                }

                if (store instanceof InMemoryEmbeddingStore) {
                    // 优先从预构建 JSON 加载向量（秒级），仅在文件缺失时走 re-embedding（分钟级）
                    if (PREBUILT_JSON.exists()) {
                        log.info("从预构建文件加载: {} ({} KB)", PREBUILT_JSON.getPath(), PREBUILT_JSON.length() / 1024);
                        int count = knowledgeFileStore.loadFromFile(store, PREBUILT_JSON);
                        knowledgeFileStore.loadLearned(store);
                        log.info("知识库就绪: {} 条 (来自预构建文件 + learned)", count);
                        return;
                    }
                }

                java.io.File file = new java.io.File(DEFAULT_DOC);
                if (!file.exists()) {
                    log.warn("默认知识库文档不存在: {}", DEFAULT_DOC);
                    return;
                }

                log.info("正在加载默认知识库: {} ({} KB)", DEFAULT_DOC, file.length() / 1024);

                try (InputStream in = new FileInputStream(file)) {
                    DocumentParser parser = new ApacheTikaDocumentParser();
                    Document doc = parser.parse(in);

                    List<TextSegment> segments = TextSplitter.splitByHeadings(doc, 800, 50);

                    for (TextSegment seg : segments) {
                        seg.metadata().put("filename", file.getName());
                        seg.metadata().put("category", "八股");
                    }

                    Response<List<Embedding>> embeddings = model.embedAll(segments);
                    hybridSearch.addSegments(segments, embeddings.content());

                    log.info("知识库就绪: {} 个片段已写入内存向量库", segments.size());
                }
            }
        };
    }

    private static boolean storeHasData(PgVectorEmbeddingStore store, EmbeddingModel model) {
        try {
            Embedding probe = model.embed("__health__").content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(probe)
                    .query("__health__")
                    .maxResults(1)
                    .minScore(0.0)
                    .build();
            return !store.search(request).matches().isEmpty();
        } catch (Exception e) {
            log.debug("PgVector 健康检查异常: {}", e.getMessage());
            return false;
        }
    }
}
