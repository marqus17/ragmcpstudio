package com.ragmcp.studio.application;

import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.ScoredChunk;
import com.ragmcp.studio.domain.model.SearchResult;
import com.ragmcp.studio.domain.model.SourceSummary;
import com.ragmcp.studio.domain.port.EmbeddingPort;
import com.ragmcp.studio.domain.port.VectorStorePort;
import com.ragmcp.studio.domain.service.RrfFusion;

import java.util.List;
import java.util.Objects;

public class SearchService {

    public static final int DEFAULT_TOP_K = 5;
    private static final int CANDIDATE_MULTIPLIER = 4;
    private static final int MIN_CANDIDATES = 20;

    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStore;
    private final RrfFusion rrfFusion;

    public SearchService(
            EmbeddingPort embeddingPort,
            VectorStorePort vectorStore,
            RrfFusion rrfFusion
    ) {
        this.embeddingPort = Objects.requireNonNull(embeddingPort, "embeddingPort must not be null");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.rrfFusion = Objects.requireNonNull(rrfFusion, "rrfFusion must not be null");
    }

    public List<SearchResult> search(String dbPath, String query, int topK) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("dbPath must not be blank");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }

        DatabaseMetadata metadata = vectorStore.readMetadata(dbPath);
        if (metadata == null || metadata.embedModel() == null || metadata.embedModel().isBlank()) {
            throw new IllegalStateException("Database metadata is missing embed_model for: " + dbPath);
        }
        if (metadata.dimensions() != embeddingPort.dimensions()) {
            throw new IllegalStateException(
                    "Database dimensions (" + metadata.dimensions() + ", model '" + metadata.embedModel()
                            + "') do not match embedded model '" + embeddingPort.modelName()
                            + "' (" + embeddingPort.dimensions() + " dimensions). Please re-index with this version.");
        }

        float[] queryEmbedding = embeddingPort.embedQuery(query);
        int candidateLimit = Math.max(MIN_CANDIDATES, topK * CANDIDATE_MULTIPLIER);

        List<ScoredChunk> vectorResults = vectorStore.searchByVector(dbPath, queryEmbedding, candidateLimit);
        List<ScoredChunk> ftsResults = vectorStore.searchByFts5(dbPath, query, candidateLimit);

        return rrfFusion.fuse(vectorResults, ftsResults, topK);
    }

    public List<SourceSummary> listIndexedSources(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("dbPath must not be blank");
        }
        return vectorStore.listIndexedSources(dbPath);
    }

    public DatabaseMetadata inspectDatabase(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("dbPath must not be blank");
        }
        return vectorStore.readMetadata(dbPath);
    }
}
