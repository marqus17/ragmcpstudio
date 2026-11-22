package com.ragmcp.studio.domain.model;

public record ScoredChunk(
        long id,
        String source,
        String title,
        String content,
        double score
) {
}
