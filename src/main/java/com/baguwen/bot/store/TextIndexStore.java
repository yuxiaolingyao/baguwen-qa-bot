package com.baguwen.bot.store;

import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TextIndexStore {

    private static final Logger log = LoggerFactory.getLogger(TextIndexStore.class);

    private final List<TextSegment> textIndex = new CopyOnWriteArrayList<>();

    public void addAll(List<TextSegment> segments) {
        textIndex.addAll(segments);
        log.debug("文本索引新增 {} 条, 总量: {}", segments.size(), textIndex.size());
    }

    public int size() {
        return textIndex.size();
    }

    public List<TextSegment> keywordSearch(String query, int topK) {
        List<TextSegment> results = new ArrayList<>();
        String[] terms = query.toLowerCase().split("\\s+");
        Map<TextSegment, Double> scores = new LinkedHashMap<>();

        for (TextSegment seg : textIndex) {
            String text = seg.text().toLowerCase();
            double score = 0;
            for (String term : terms) {
                if (term.length() < 2) continue;
                int count = 0;
                int idx = 0;
                while ((idx = text.indexOf(term, idx)) != -1) {
                    count++;
                    idx += term.length();
                }
                // log1p 压平词频差异 (5次 vs 1次差异不大)，lenNorm 对长文本做温和归一化
                if (count > 0) {
                    score += Math.log1p(count);
                }
            }
            if (score > 0) {
                double lenNorm = Math.log1p(text.length() / 100.0);
                scores.put(seg, score / lenNorm);
            }
        }

        scores.entrySet().stream()
                .sorted(Map.Entry.<TextSegment, Double>comparingByValue().reversed())
                .limit(topK)
                .forEachOrdered(new java.util.function.Consumer<Map.Entry<TextSegment, Double>>() {
                    @Override
                    public void accept(Map.Entry<TextSegment, Double> entry) {
                        results.add(entry.getKey());
                    }
                });

        log.debug("关键词检索: {} → {} 个结果", query, results.size());
        return results;
    }
}
