package com.ragmcp.studio.domain.model;

public record DatabaseMetadata(
        String embedModel,
        int dimensions,
        long documentCount,
        long chunkCount,
        String createdAt
) {
}
