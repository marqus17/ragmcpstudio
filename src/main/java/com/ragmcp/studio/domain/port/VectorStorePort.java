package com.ragmcp.studio.domain.port;

import com.ragmcp.studio.domain.model.Chunk;
import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.ScoredChunk;
import com.ragmcp.studio.domain.model.SourceSummary;

import java.util.List;

public interface VectorStorePort {

    void initializeDatabase(String dbPath, String embedModel, int dimensions);

    void saveChunks(String dbPath, List<Chunk> chunks);

    DatabaseMetadata readMetadata(String dbPath);

    List<SourceSummary> listIndexedSources(String dbPath);

    List<ScoredChunk> searchByVector(String dbPath, float[] queryEmbedding, int limit);

    List<ScoredChunk> searchByFts5(String dbPath, String query, int limit);

    void evictCache(String dbPath);
}
