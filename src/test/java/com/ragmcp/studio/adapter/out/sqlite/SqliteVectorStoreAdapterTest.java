package com.ragmcp.studio.adapter.out.sqlite;

import com.ragmcp.studio.domain.model.Chunk;
import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.ScoredChunk;
import com.ragmcp.studio.domain.model.SourceSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteVectorStoreAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeDatabaseCreatesSchemaMetadataAndReplacesExistingData() {
        SqliteVectorStoreAdapter adapter = new SqliteVectorStoreAdapter();
        Path dbFile = tempDir.resolve("nested/dir/rag.sqlite");

        adapter.initializeDatabase(dbFile.toString(), "nomic-embed-text", 3);
        assertThat(Files.isRegularFile(dbFile)).isTrue();

        adapter.saveChunks(dbFile.toString(), List.of(
                new Chunk(null, "docs/a.md", "Doc A", "Alpha content", new float[]{1.0f, 0.0f, 0.0f}),
                new Chunk(null, "docs/a.md", "Doc A", "Alpha second chunk", new float[]{0.9f, 0.1f, 0.0f}),
                new Chunk(null, "docs/b.md", "Doc B", "Beta content", new float[]{0.0f, 1.0f, 0.0f})
        ));

        DatabaseMetadata meta = adapter.readMetadata(dbFile.toString());
        assertThat(meta.embedModel()).isEqualTo("nomic-embed-text");
        assertThat(meta.dimensions()).isEqualTo(3);
        assertThat(meta.documentCount()).isEqualTo(2L);
        assertThat(meta.chunkCount()).isEqualTo(3L);
        assertThat(meta.createdAt()).isNotBlank();
        assertThat(Instant.parse(meta.createdAt())).isNotNull();

        List<SourceSummary> sources = adapter.listIndexedSources(dbFile.toString());
        assertThat(sources).containsExactly(
                new SourceSummary("docs/a.md", 2L),
                new SourceSummary("docs/b.md", 1L)
        );

        // Re-initializing replaces old rows cleanly
        adapter.initializeDatabase(dbFile.toString(), "mxbai-embed-large", 4);
        DatabaseMetadata resetMeta = adapter.readMetadata(dbFile.toString());
        assertThat(resetMeta.embedModel()).isEqualTo("mxbai-embed-large");
        assertThat(resetMeta.dimensions()).isEqualTo(4);
        assertThat(resetMeta.documentCount()).isEqualTo(0L);
        assertThat(resetMeta.chunkCount()).isEqualTo(0L);
        assertThat(adapter.listIndexedSources(dbFile.toString())).isEmpty();
    }

    @Test
    void readMetadataRejectsMissingOrInvalidDatabase() throws Exception {
        SqliteVectorStoreAdapter adapter = new SqliteVectorStoreAdapter();

        Path missing = tempDir.resolve("missing.sqlite");
        assertThatThrownBy(() -> adapter.readMetadata(missing.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");

        Path invalidDb = tempDir.resolve("not-rag.sqlite");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + invalidDb);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE other (id INTEGER PRIMARY KEY)");
        }
        assertThatThrownBy(() -> adapter.readMetadata(invalidDb.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not a valid RAG");
    }

    @Test
    void searchByVectorRanksByCosineSimilarityAndRespectsCacheEviction() {
        SqliteVectorStoreAdapter adapter = new SqliteVectorStoreAdapter();
        Path dbFile = tempDir.resolve("vectors.sqlite");

        adapter.initializeDatabase(dbFile.toString(), "test-model", 3);
        adapter.saveChunks(dbFile.toString(), List.of(
                new Chunk(null, "a.md", "X Axis", "Points along X", new float[]{1.0f, 0.0f, 0.0f}),
                new Chunk(null, "b.md", "XY Diagonal", "Points along XY", new float[]{0.7071f, 0.7071f, 0.0f}),
                new Chunk(null, "c.md", "Z Axis", "Points along Z", new float[]{0.0f, 0.0f, 1.0f})
        ));

        float[] query = new float[]{1.0f, 0.0f, 0.0f};
        List<ScoredChunk> results = adapter.searchByVector(dbFile.toString(), query, 2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).source()).isEqualTo("a.md");
        assertThat(results.get(0).score()).isGreaterThan(0.99);
        assertThat(results.get(1).source()).isEqualTo("b.md");
        assertThat(results.get(1).score()).isBetween(0.69, 0.72);

        // Second call uses in-memory cache and returns identical results
        List<ScoredChunk> cachedResults = adapter.searchByVector(dbFile.toString(), query, 2);
        assertThat(cachedResults).isEqualTo(results);

        // Explicit eviction + new chunk insert
        adapter.evictCache(dbFile.toString());
        adapter.saveChunks(dbFile.toString(), List.of(
                new Chunk(null, "d.md", "Almost X", "Very close to X", new float[]{0.99f, 0.01f, 0.0f})
        ));

        List<ScoredChunk> updated = adapter.searchByVector(dbFile.toString(), query, 2);
        assertThat(updated).extracting(ScoredChunk::source).containsExactly("a.md", "d.md");
    }

    @Test
    void searchByFts5HandlesSpecialCharactersAndTriggerSync() throws Exception {
        SqliteVectorStoreAdapter adapter = new SqliteVectorStoreAdapter();
        Path dbFile = tempDir.resolve("fts.sqlite");

        adapter.initializeDatabase(dbFile.toString(), "test-model", 2);
        adapter.saveChunks(dbFile.toString(), List.of(
                new Chunk(null, "cpp.md", "C++ Guide", "Modern c++ memory management and foo:bar syntax", new float[]{1.0f, 0.0f}),
                new Chunk(null, "java.md", "Java Guide", "Virtual threads in Java 21 with structured concurrency", new float[]{0.0f, 1.0f}),
                new Chunk(null, "ops.md", "Boolean Ops", "Handling AND OR NOT operators safely in queries", new float[]{0.5f, 0.5f})
        ));

        // Special characters and punctuation never throw FTS5 syntax errors
        List<ScoredChunk> cppHits = adapter.searchByFts5(dbFile.toString(), "c++ foo:bar a-b \"unclosed", 5);
        assertThat(cppHits).isNotEmpty();
        assertThat(cppHits.get(0).source()).isEqualTo("cpp.md");
        assertThat(cppHits.get(0).score()).isGreaterThanOrEqualTo(0.0);

        List<ScoredChunk> keywordHits = adapter.searchByFts5(dbFile.toString(), "AND OR NOT", 5);
        assertThat(keywordHits).extracting(ScoredChunk::source).contains("ops.md");

        // Pure punctuation query returns empty list safely
        assertThat(adapter.searchByFts5(dbFile.toString(), "+++ --- ::: \"\"", 5)).isEmpty();

        // Verify UPDATE (chunks_au) and DELETE (chunks_ad) triggers keep chunks_fts in sync
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE chunks SET content = 'Rust borrow checker ownership' WHERE source = 'java.md'");
            stmt.executeUpdate("DELETE FROM chunks WHERE source = 'ops.md'");
        }

        assertThat(adapter.searchByFts5(dbFile.toString(), "structured concurrency", 5)).isEmpty();
        List<ScoredChunk> rustHits = adapter.searchByFts5(dbFile.toString(), "borrow checker", 5);
        assertThat(rustHits).hasSize(1);
        assertThat(rustHits.get(0).source()).isEqualTo("java.md");

        assertThat(adapter.searchByFts5(dbFile.toString(), "operators safely", 5)).isEmpty();
    }
}
