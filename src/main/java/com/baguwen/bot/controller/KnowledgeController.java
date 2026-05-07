package com.baguwen.bot.controller;

import com.baguwen.bot.service.DocumentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final DocumentService documentService;

    public KnowledgeController(DocumentService documentService) {
        this.documentService = documentService;
    }

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam(defaultValue = "未分类") String category) {
        if (file == null || file.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("ok", false);
            error.put("error", "文件为空");
            return error;
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            Map<String, Object> error = new HashMap<>();
            error.put("ok", false);
            error.put("error", "文件大小超过限制 (最大 10MB)");
            return error;
        }

        try (var in = file.getInputStream()) {
            documentService.ingest(in, file.getOriginalFilename(), category);
            Map<String, Object> result = new HashMap<>();
            result.put("ok", true);
            result.put("filename", file.getOriginalFilename());
            result.put("category", category);
            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("ok", false);
            error.put("error", e.getMessage());
            return error;
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        result.put("store", "PgVector (PostgreSQL)");
        result.put("searchMode", "HYBRID (vector + BM25 + RRF)");
        result.put("description", "知识库已就绪，可通过 /api/knowledge/upload 上传文档");
        return result;
    }
}
