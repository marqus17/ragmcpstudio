package com.ragmcp.studio.adapter.out.sqlite;

import com.ragmcp.studio.domain.model.Chunk;
import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.ScoredChunk;
import com.ragmcp.studio.domain.model.SourceSummary;
import com.ragmcp.studio.domain.port.VectorStorePort;
import com.ragmcp.studio.domain.service.VectorMath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqliteVectorStoreAdapter implements VectorStorePort {

    private static final String KEY_EMBED_MODEL = "embed_model";
    private static final String KEY_DIMENSIONS = "dimensions";
    private static final String KEY_CREATED_AT = "created_at";
    private static final Pattern FTS_TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private final ConcurrentHashMap<String, CachedDb> cache = new ConcurrentHashMap<>();

    public SqliteVectorStoreAdapter() {
    }

    @Override
    public void initializeDatabase(String dbPath, String embedModel, int dimensions) {
        Path path = normalizePath(dbPath);
        if (embedModel == null || embedModel.isBlank()) {
            throw new IllegalArgumentException("embedModel must not be blank");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be > 0");
        }

        ensureParentDirectory(path);

        try (Connection conn = openConnection(path, true)) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TRIGGER IF EXISTS chunks_ai");
                stmt.execute("DROP TRIGGER IF EXISTS chunks_ad");
                stmt.execute("DROP TRIGGER IF EXISTS chunks_au");
                stmt.execute("DROP TABLE IF EXISTS chunks_fts");
                stmt.execute("DROP TABLE IF EXISTS chunks");
                stmt.execute("DROP TABLE IF EXISTS metadata");

                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS metadata (
                            key TEXT PRIMARY KEY,
                            value TEXT
                        )
                        """);

                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS chunks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            source TEXT NOT NULL,
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            embedding BLOB NOT NULL
                        )
                        """);

                stmt.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
                            content,
                            title,
                            content='chunks',
                            content_rowid='id'
                        )
                        """);

                stmt.execute("""
                        CREATE TRIGGER IF NOT EXISTS chunks_ai AFTER INSERT ON chunks BEGIN
                            INSERT INTO chunks_fts(rowid, content, title)
                            VALUES (new.id, new.content, new.title);
                        END
                        """);

                stmt.execute("""
                        CREATE TRIGGER IF NOT EXISTS chunks_ad AFTER DELETE ON chunks BEGIN
                            INSERT INTO chunks_fts(chunks_fts, rowid, content, title)
                            VALUES ('delete', old.id, old.content, old.title);
                        END
                        """);

                stmt.execute("""
                        CREATE TRIGGER IF NOT EXISTS chunks_au AFTER UPDATE ON chunks BEGIN
                            INSERT INTO chunks_fts(chunks_fts, rowid, content, title)
                            VALUES ('delete', old.id, old.content, old.title);
                            INSERT INTO chunks_fts(rowid, content, title)
                            VALUES (new.id, new.content, new.title);
                        END
                        """);

                String createdAt = Instant.now().toString();
                try (PreparedStatement insertMeta = conn.prepareStatement(
                        "INSERT INTO metadata (key, value) VALUES (?, ?)")) {
                    insertMetadataRow(insertMeta, KEY_EMBED_MODEL, embedModel);
                    insertMetadataRow(insertMeta, KEY_DIMENSIONS, Integer.toString(dimensions));
                    insertMetadataRow(insertMeta, KEY_CREATED_AT, createdAt);
                    insertMeta.executeBatch();
                }

                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite database at " + path + ": " + e.getMessage(), e);
        } finally {
            evictCache(path.toString());
        }
    }

    @Override
    public void saveChunks(String dbPath, List<Chunk> chunks) {
        Path path = normalizePath(dbPath);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        requireExistingDatabaseFile(path);

        try (Connection conn = openConnection(path, true)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO chunks (source, title, content, embedding) VALUES (?, ?, ?, ?)")) {
                for (Chunk chunk : chunks) {
                    if (chunk == null) {
                        throw new IllegalArgumentException("Chunk list must not contain null elements");
                    }
                    float[] embedding = chunk.embedding();
                    if (embedding == null || embedding.length == 0) {
                        throw new IllegalArgumentException("Chunk embedding must not be null or empty for source: " + chunk.source());
                    }
                    ps.setString(1, chunk.source());
                    ps.setString(2, chunk.title());
                    ps.setString(3, chunk.content());
                    ps.setBytes(4, VectorMath.toBytes(embedding));
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save chunks to SQLite database at " + path + ": " + e.getMessage(), e);
        } finally {
            evictCache(path.toString());
        }
    }

    @Override
    public DatabaseMetadata readMetadata(String dbPath) {
        Path path = normalizePath(dbPath);
        requireExistingDatabaseFile(path);

        try (Connection conn = openConnection(path, false)) {
            Map<String, String> meta = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT key, value FROM metadata")) {
                while (rs.next()) {
                    meta.put(rs.getString(1), rs.getString(2));
                }
            }

            String embedModel = meta.get(KEY_EMBED_MODEL);
            String dimensionsStr = meta.get(KEY_DIMENSIONS);
            String createdAt = meta.getOrDefault(KEY_CREATED_AT, "");

            if (embedModel == null || embedModel.isBlank()) {
                throw new IllegalStateException("Not a valid RAG database (missing 'embed_model' in metadata): " + path);
            }
            if (dimensionsStr == null || dimensionsStr.isBlank()) {
                throw new IllegalStateException("Not a valid RAG database (missing 'dimensions' in metadata): " + path);
            }

            int dimensions;
            try {
                dimensions = Integer.parseInt(dimensionsStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid 'dimensions' metadata value '" + dimensionsStr + "' in " + path, e);
            }

            long documentCount = 0L;
            long chunkCount = 0L;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT source), COUNT(*) FROM chunks")) {
                if (rs.next()) {
                    documentCount = rs.getLong(1);
                    chunkCount = rs.getLong(2);
                }
            }

            return new DatabaseMetadata(embedModel, dimensions, documentCount, chunkCount, createdAt);
        } catch (SQLException e) {
            throw new IllegalStateException("Not a valid RAG SQLite database at " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<SourceSummary> listIndexedSources(String dbPath) {
        Path path = normalizePath(dbPath);
        requireExistingDatabaseFile(path);

        List<SourceSummary> summaries = new ArrayList<>();
        try (Connection conn = openConnection(path, false);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT source, COUNT(*) FROM chunks GROUP BY source ORDER BY source")) {
            while (rs.next()) {
                summaries.add(new SourceSummary(rs.getString(1), rs.getLong(2)));
            }
            return List.copyOf(summaries);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list indexed sources from " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<ScoredChunk> searchByVector(String dbPath, float[] queryEmbedding, int limit) {
        Path path = normalizePath(dbPath);
        requireExistingDatabaseFile(path);

        if (queryEmbedding == null || queryEmbedding.length == 0 || limit <= 0) {
            return List.of();
        }

        CachedDb cachedDb = getOrLoadCache(path);
        List<CachedChunk> chunks = cachedDb.chunks();
        if (chunks.isEmpty()) {
            return List.of();
        }

        // Min-heap of size `limit` ordered by lowest score first (tie-break highest id first)
        Comparator<ScoredChunk> minHeapOrder = Comparator
                .comparingDouble(ScoredChunk::score)
                .thenComparing(Comparator.comparingLong(ScoredChunk::id).reversed());
        PriorityQueue<ScoredChunk> topHeap = new PriorityQueue<>(Math.min(limit, chunks.size()) + 1, minHeapOrder);

        for (CachedChunk chunk : chunks) {
            double score = VectorMath.cosineSimilarity(queryEmbedding, chunk.embedding());
            if (topHeap.size() < limit) {
                topHeap.offer(new ScoredChunk(chunk.id(), chunk.source(), chunk.title(), chunk.content(), score));
            } else {
                ScoredChunk lowest = topHeap.peek();
                if (lowest != null && (score > lowest.score()
                        || (score == lowest.score() && chunk.id() < lowest.id()))) {
                    topHeap.poll();
                    topHeap.offer(new ScoredChunk(chunk.id(), chunk.source(), chunk.title(), chunk.content(), score));
                }
            }
        }

        List<ScoredChunk> results = new ArrayList<>(topHeap);
        results.sort(Comparator
                .comparingDouble(ScoredChunk::score).reversed()
                .thenComparingLong(ScoredChunk::id));
        return List.copyOf(results);
    }

    @Override
    public List<ScoredChunk> searchByFts5(String dbPath, String query, int limit) {
        Path path = normalizePath(dbPath);
        requireExistingDatabaseFile(path);

        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        String sanitizedQuery = sanitizeFts5Query(query);
        if (sanitizedQuery.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> results = new ArrayList<>();
        String sql = """
                SELECT c.id, c.source, c.title, c.content, bm25(chunks_fts) AS rank
                FROM chunks_fts
                JOIN chunks c ON c.id = chunks_fts.rowid
                WHERE chunks_fts MATCH ?
                ORDER BY rank ASC, c.id ASC
                LIMIT ?
                """;

        try (Connection conn = openConnection(path, false);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sanitizedQuery);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    String source = rs.getString(2);
                    String title = rs.getString(3);
                    String content = rs.getString(4);
                    double bm25Rank = rs.getDouble(5);
                    // SQLite bm25() returns negative values where more negative = better match.
                    double positiveScore = bm25Rank < 0.0 ? -bm25Rank : 0.0;
                    results.add(new ScoredChunk(id, source, title, content, positiveScore));
                }
            }
            return List.copyOf(results);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run FTS5 search on " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void evictCache(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            return;
        }
        String key = Path.of(dbPath).toAbsolutePath().normalize().toString();
        cache.remove(key);
    }

    static String sanitizeFts5Query(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = FTS_TOKEN_PATTERN.matcher(rawQuery);
        while (matcher.find()) {
            String token = matcher.group();
            if (!token.isEmpty()) {
                tokens.add("\"" + token.replace("\"", "\"\"") + "\"");
            }
        }
        if (tokens.isEmpty()) {
            return "";
        }
        return String.join(" OR ", tokens);
    }

    private CachedDb getOrLoadCache(Path path) {
        String key = path.toString();
        FileFingerprint current = FileFingerprint.of(path);
        CachedDb existing = cache.get(key);
        if (existing != null && existing.fingerprint().equals(current)) {
            return existing;
        }

        List<CachedChunk> loaded = loadAllChunks(path);
        CachedDb updated = new CachedDb(current, loaded);
        cache.put(key, updated);
        return updated;
    }

    private List<CachedChunk> loadAllChunks(Path path) {
        List<CachedChunk> loaded = new ArrayList<>();
        try (Connection conn = openConnection(path, false);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, source, title, content, embedding FROM chunks")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String source = rs.getString(2);
                String title = rs.getString(3);
                String content = rs.getString(4);
                byte[] blob = rs.getBytes(5);
                float[] embedding = blob == null ? new float[0] : VectorMath.toFloats(blob);
                loaded.add(new CachedChunk(id, source, title, content, embedding));
            }
            return List.copyOf(loaded);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load vectors from SQLite database at " + path + ": " + e.getMessage(), e);
        }
    }

    private Connection openConnection(Path path, boolean enableWal) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA busy_timeout = 5000");
            if (enableWal) {
                pragma.execute("PRAGMA journal_mode = WAL");
            }
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    private static void insertMetadataRow(PreparedStatement ps, String key, String value) throws SQLException {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.addBatch();
    }

    private static Path normalizePath(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("dbPath must not be blank");
        }
        return Path.of(dbPath).toAbsolutePath().normalize();
    }

    private static void requireExistingDatabaseFile(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("SQLite database file does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("SQLite database path is not a regular file: " + path);
        }
    }

    private static void ensureParentDirectory(Path path) {
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create parent directory for database: " + parent, e);
            }
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Suppress rollback failure in favor of original exception
        }
    }

    private record CachedChunk(
            long id,
            String source,
            String title,
            String content,
            float[] embedding
    ) {
    }

    private record FileFingerprint(
            long lastModifiedMillis,
            long fileSize,
            long walLastModifiedMillis,
            long walFileSize
    ) {
        static FileFingerprint of(Path path) {
            try {
                long mtime = Files.getLastModifiedTime(path).toMillis();
                long size = Files.size(path);
                Path walPath = Path.of(path.toString() + "-wal");
                long walMtime = 0L;
                long walSize = 0L;
                if (Files.exists(walPath)) {
                    walMtime = Files.getLastModifiedTime(walPath).toMillis();
                    walSize = Files.size(walPath);
                }
                return new FileFingerprint(mtime, size, walMtime, walSize);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to inspect database file metadata: " + path, e);
            }
        }
    }

    private record CachedDb(
            FileFingerprint fingerprint,
            List<CachedChunk> chunks
    ) {
    }
}
