package com.ragmcp.studio.domain.model;

public record SearchResult(
        long chunkId,
        String source,
        String title,
        String content,
        double score,
        double vectorScore,
        double ftsScore
) {
}
