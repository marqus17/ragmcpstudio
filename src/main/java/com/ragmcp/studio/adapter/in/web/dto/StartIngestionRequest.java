package com.ragmcp.studio.adapter.in.web.dto;

public record StartIngestionRequest(
        String sourceDir,
        String dbPath,
        Integer chunkSize,
        Integer chunkOverlap
) {
}
