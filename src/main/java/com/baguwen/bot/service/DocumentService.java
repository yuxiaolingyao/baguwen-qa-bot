package com.baguwen.bot.service;

import com.baguwen.bot.util.TextSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final EmbeddingModel embeddingModel;
    private final HybridSearchService hybridSearch;

    public DocumentService(EmbeddingModel embeddingModel,
                            HybridSearchService hybridSearch) {
        this.embeddingModel = embeddingModel;
        this.hybridSearch = hybridSearch;
    }

    /**
     * 上传文档，解析 → 切片 → 向量化 → 入库。
     * @param inputStream 文档输入流
     * @param filename   文件名（用于溯源）
     * @param category   分类标签（JVM / Spring / 并发 等）
     */
    public void ingest(InputStream inputStream, String filename, String category) {
        log.info("开始入库: {} (分类: {})", filename, category);

        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document = parser.parse(inputStream);

        List<TextSegment> segments = TextSplitter.splitByHeadings(document, 800, 50);

        for (TextSegment seg : segments) {
            seg.metadata().put("filename", filename);
            seg.metadata().put("category", category);
        }

        Response<List<Embedding>> embeddingsResp = embeddingModel.embedAll(segments);
        hybridSearch.addSegments(segments, embeddingsResp.content());

        log.info("入库完成: {} → {} 个片段", filename, segments.size());
    }

}
