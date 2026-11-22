package com.ragmcp.studio.adapter.in.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragmcp.studio.application.SearchService;
import com.ragmcp.studio.domain.model.Chunk;
import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.McpServerStatus;
import com.ragmcp.studio.domain.model.ScoredChunk;
import com.ragmcp.studio.domain.model.SourceSummary;
import com.ragmcp.studio.domain.port.EmbeddingPort;
import com.ragmcp.studio.domain.port.VectorStorePort;
import com.ragmcp.studio.domain.service.RrfFusion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicMcpServerAdapterTest {

    private static final String TEST_DB_PATH = "/tmp/test-rag.db";

    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private StubEmbeddingPort embeddingPort;
    private StubVectorStorePort vectorStorePort;
    private DynamicMcpServerAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        embeddingPort = new StubEmbeddingPort();
        vectorStorePort = new StubVectorStorePort();
        SearchService searchService = new SearchService(embeddingPort, vectorStorePort, new RrfFusion());
        adapter = new DynamicMcpServerAdapter(searchService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    void fullMcpProtocolFlow() throws Exception {
        McpServerStatus started = adapter.start(0, TEST_DB_PATH);
        assertThat(started.running()).isTrue();
        assertThat(started.port()).isPositive();
        assertThat(started.endpointUrl()).isEqualTo("http://localhost:" + started.port() + "/mcp");
        assertThat(started.activeDbPath()).isEqualTo(TEST_DB_PATH);
        assertThat(started.metadata()).isNotNull();
        assertThat(started.metadata().embedModel()).isEqualTo("e5-small-v2-q");
        assertThat(started.lastError()).isNull();

        URI endpoint = URI.create(started.endpointUrl());

        // 1. initialize
        HttpResponse<String> initResp = postJson(endpoint, """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": { "name": "test-client", "version": "1.0" }
                  }
                }
                """, null);

        assertThat(initResp.statusCode()).isEqualTo(200);
        assertThat(initResp.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertThat(sessionId).isNotBlank();

        JsonNode initJson = objectMapper.readTree(initResp.body());
        assertThat(initJson.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(initJson.path("id").asInt()).isEqualTo(1);
        assertThat(initJson.path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(initJson.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(initJson.path("result").path("serverInfo").path("name").asText()).isEqualTo("rag-mcp-studio");

        // 2. notifications/initialized -> 202 Accepted
        HttpResponse<String> notifResp = postJson(endpoint, """
                {
                  "jsonrpc": "2.0",
                  "method": "notifications/initialized"
                }
                """, sessionId);
        assertThat(notifResp.statusCode()).isEqualTo(202);
        assertThat(notifResp.body()).isEmpty();

        // 3. ping
        HttpResponse<String> pingResp = postJson(endpoint, """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "ping"
                }
                """, sessionId);
        assertThat(pingResp.statusCode()).isEqualTo(200);
        JsonNode pingJson = objectMapper.readTree(pingResp.body());
        assertThat(pingJson.path("result").isObject()).isTrue();
        assertThat(pingJson.path("result").size()).isZero();

        // 4. tools/list
        HttpResponse<String> listResp = postJson(endpoint, """
                {
                  "jsonrpc": "2.0",
                  "id": 3,
                  "method": "tools/list"
                }
                """, sessionId);
        assertThat(listResp.statusCode()).isEqualTo(200);
        JsonNode tools = objectMapper.readTree(listResp.body()).path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools).hasSize(2);

        JsonNode searchTool = tools.get(0);
        assertThat(searchTool.path("name").asText()).isEqualTo("search_docs");
        assertThat(searchTool.path("inputSchema").path("properties").has("query")).isTrue();
        assertThat(searchTool.path("inputSchema").path("properties").has("top_k")).isTrue();
        assertThat(searchTool.path("inputSchema").path("required").get(0).asText()).isEqualTo("query");

        JsonNode listSourcesTool = tools.get(1);
        assertThat(listSourcesTool.path("name").asText()).isEqualTo("list_indexed_sources");
        assertThat(listSourcesTool.path("inputSchema").path("type").asText()).isEqualTo("object");
        assertThat(listSourcesTool.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();

        // 5. tools/call -> search_docs
        HttpResponse<String> searchResp = postJson(endpoint, """
                {
                  "jsonrpc": "2.0",
                  "id": 4,
                  "method": "tools/call",
                  "params": {
                    "name": "search_docs",
                    "arguments": {
                      "query": "hexagonal architecture",
                      "top_k": 3
                    }
                  }
                }
                """, sessionId);
        assertThat(searchResp.statusCode()).isEqualTo(200);
        JsonNode searchResultNode = objectMapper.readTree(searchResp.body()).path("result");
        assertThat(searchResultNode.path("isError").asBoolean()).isFalse();
        String searchText = searchResultNode.path("content").get(0).path("text").asText();
        assertThat(searchText)
                .contains("docs/architecture.md")
                .contains("Hexagonal Overview")
                .contains("Score:")
                .contains("vector:")
                .contains("fts:")
                .contains("Clean hexagonal architecture separates domain from adapters.");

        // 6. tools/call -> list_indexed_sources
        HttpResponse<String> sourcesResp = postJson(endpoint, """
                {
                  "jsonrpc": "2.0",
                  "id": 5,
                  "method": "tools/call",
                  "params": {
                    "name": "list_indexed_sources",
                    "arguments": {}
                  }
                }
                """, sessionId);
        assertThat(sourcesResp.statusCode()).isEqualTo(200);
        JsonNode sourcesResultNode = objectMapper.readTree(sourcesResp.body()).path("result");
        assertThat(sourcesResultNode.path("isError").asBoolean()).isFalse();
        String sourcesText = sourcesResultNode.path("content").get(0).path("text").asText();
        assertThat(sourcesText)
                .contains("docs/architecture.md")
                .contains("12 chunks")
                .contains("README.md")
                .contains("4 chunks");

        // 7. DELETE /mcp closes session cleanly
        HttpRequest deleteReq = HttpRequest.newBuilder(endpoint)
                .DELETE()
                .header("Mcp-Session-Id", sessionId)
                .build();
        HttpResponse<String> deleteResp = httpClient.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertThat(deleteResp.statusCode()).isEqualTo(204);
    }

    @Test
    void handlesErrorsAndEdgeCases() throws Exception {
        McpServerStatus started = adapter.start(0, TEST_DB_PATH);
        URI endpoint = URI.create(started.endpointUrl());

        // Invalid JSON -> -32700
        HttpResponse<String> badJsonResp = postJson(endpoint, "{ invalid json", null);
        assertThat(badJsonResp.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(badJsonResp.body()).path("error").path("code").asInt()).isEqualTo(-32700);

        // Unknown method -> -32601
        HttpResponse<String> unknownMethodResp = postJson(endpoint, """
                {"jsonrpc":"2.0","id":10,"method":"unknown/method"}
                """, null);
        assertThat(unknownMethodResp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(unknownMethodResp.body()).path("error").path("code").asInt()).isEqualTo(-32601);

        // Unknown tool -> -32602
        HttpResponse<String> unknownToolResp = postJson(endpoint, """
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"does_not_exist","arguments":{}}}
                """, null);
        assertThat(unknownToolResp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(unknownToolResp.body()).path("error").path("code").asInt()).isEqualTo(-32602);

        // Search failure (e.g. embedding failure) -> isError: true
        embeddingPort.failOnEmbed.set(true);
        HttpResponse<String> failedSearchResp = postJson(endpoint, """
                {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"search_docs","arguments":{"query":"hello"}}}
                """, null);
        assertThat(failedSearchResp.statusCode()).isEqualTo(200);
        JsonNode failedResult = objectMapper.readTree(failedSearchResp.body()).path("result");
        assertThat(failedResult.path("isError").asBoolean()).isTrue();
        assertThat(failedResult.path("content").get(0).path("text").asText()).contains("Embedding engine failed");

        // OPTIONS preflight
        HttpRequest optionsReq = HttpRequest.newBuilder(endpoint)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> optionsResp = httpClient.send(optionsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(optionsResp.statusCode()).isEqualTo(204);
        assertThat(optionsResp.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
    }

    @Test
    void lifecycleStopAndRestart() throws Exception {
        McpServerStatus first = adapter.start(0, TEST_DB_PATH);
        assertThat(first.running()).isTrue();
        int firstPort = first.port();

        McpServerStatus stopped = adapter.stop();
        assertThat(stopped.running()).isFalse();
        assertThat(stopped.port()).isZero();
        assertThat(adapter.status().running()).isFalse();

        // Restart on another ephemeral port
        McpServerStatus restarted = adapter.restart(0, TEST_DB_PATH);
        assertThat(restarted.running()).isTrue();
        assertThat(restarted.port()).isPositive();
        assertThat(restarted.endpointUrl()).isEqualTo("http://localhost:" + restarted.port() + "/mcp");

        // Verify restarted server responds to ping
        HttpResponse<String> pingResp = postJson(URI.create(restarted.endpointUrl()), """
                {"jsonrpc":"2.0","id":99,"method":"ping"}
                """, null);
        assertThat(pingResp.statusCode()).isEqualTo(200);

        // Restart while already running should stop old and start cleanly
        McpServerStatus restartedAgain = adapter.restart(firstPort, TEST_DB_PATH);
        assertThat(restartedAgain.running()).isTrue();
        assertThat(restartedAgain.port()).isEqualTo(firstPort);
    }

    @Test
    void startFailsCleanlyOnInvalidDatabase() {
        vectorStorePort.failMetadata.set(true);

        assertThatThrownBy(() -> adapter.start(0, "/nonexistent.db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database not found");

        McpServerStatus status = adapter.status();
        assertThat(status.running()).isFalse();
        assertThat(status.lastError()).contains("Database not found");
    }

    private HttpResponse<String> postJson(URI endpoint, String body, String sessionId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class StubEmbeddingPort implements EmbeddingPort {
        final AtomicBoolean failOnEmbed = new AtomicBoolean(false);

        @Override
        public String modelName() {
            return "e5-small-v2-q";
        }

        @Override
        public int dimensions() {
            return 3;
        }

        @Override
        public float[] embedDocument(String text) {
            return embedQuery(text);
        }

        @Override
        public float[] embedQuery(String text) {
            if (failOnEmbed.get()) {
                throw new IllegalStateException("Embedding engine failed");
            }
            return new float[]{0.5f, 0.5f, 0.5f};
        }
    }

    private static final class StubVectorStorePort implements VectorStorePort {
        final AtomicBoolean failMetadata = new AtomicBoolean(false);

        @Override
        public void initializeDatabase(String dbPath, String embedModel, int dimensions) {
        }

        @Override
        public void saveChunks(String dbPath, List<Chunk> chunks) {
        }

        @Override
        public DatabaseMetadata readMetadata(String dbPath) {
            if (failMetadata.get()) {
                throw new IllegalStateException("Database not found: " + dbPath);
            }
            return new DatabaseMetadata("e5-small-v2-q", 3, 2, 16, "2026-11-22T10:00:00Z");
        }

        @Override
        public List<SourceSummary> listIndexedSources(String dbPath) {
            return List.of(
                    new SourceSummary("docs/architecture.md", 12),
                    new SourceSummary("README.md", 4)
            );
        }

        @Override
        public List<ScoredChunk> searchByVector(String dbPath, float[] queryEmbedding, int limit) {
            return List.of(
                    new ScoredChunk(1L, "docs/architecture.md", "Hexagonal Overview",
                            "Clean hexagonal architecture separates domain from adapters.", 0.92)
            );
        }

        @Override
        public List<ScoredChunk> searchByFts5(String dbPath, String query, int limit) {
            return List.of(
                    new ScoredChunk(1L, "docs/architecture.md", "Hexagonal Overview",
                            "Clean hexagonal architecture separates domain from adapters.", 4.5)
            );
        }

        @Override
        public void evictCache(String dbPath) {
        }
    }
}
