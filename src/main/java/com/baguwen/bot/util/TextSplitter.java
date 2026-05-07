package com.baguwen.bot.util;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

public final class TextSplitter {

    private TextSplitter() {
    }

    public static List<TextSegment> splitByHeadings(Document doc, int maxChars, int overlap) {
        DocumentSplitter innerSplitter = DocumentSplitters.recursive(maxChars, overlap);
        List<TextSegment> result = new ArrayList<>();

        String text = doc.text();
        if (text == null || text.isEmpty()) {
            return result;
        }

        Metadata baseMetadata = doc.metadata() != null ? doc.metadata().copy() : new Metadata();

        // (?m) 多行模式确保文档开头的首个标题也能正确识别
        String[] sections = text.split("(?m)(?=^#{2,4} )");
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= maxChars) {
                result.add(TextSegment.from(trimmed, baseMetadata.copy()));
            } else {
                Document subDoc = Document.from(trimmed, baseMetadata.copy());
                result.addAll(innerSplitter.split(subDoc));
            }
        }
        return result;
    }
}
