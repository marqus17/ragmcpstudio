package com.ragmcp.studio.domain.model;

public record IngestionConfig(
        String sourceDir,
        String dbPath,
        int chunkSize,
        int chunkOverlap
) {
    public static final int DEFAULT_CHUNK_SIZE = 1000;
    public static final int DEFAULT_CHUNK_OVERLAP = 200;

    public IngestionConfig {
        if (sourceDir == null || sourceDir.isBlank()) {
            throw new IllegalArgumentException("sourceDir must not be blank");
        }
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("dbPath must not be blank");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
        }
        sourceDir = sourceDir.trim();
        dbPath = dbPath.trim();
    }

    public IngestionConfig(String sourceDir, String dbPath) {
        this(sourceDir, dbPath, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }
}
