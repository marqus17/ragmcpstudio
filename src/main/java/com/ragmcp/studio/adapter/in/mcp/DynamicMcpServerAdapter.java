package com.ragmcp.studio.adapter.in.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragmcp.studio.application.SearchService;
import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.McpServerStatus;
import com.ragmcp.studio.domain.model.SearchResult;
import com.ragmcp.studio.domain.model.SourceSummary;
import com.ragmcp.studio.domain.port.McpServerControllerPort;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DynamicMcpServerAdapter implements McpServerControllerPort, AutoCloseable {

    public static final String MCP_PATH = "/mcp";
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";
    public static final String SERVER_NAME = "rag-mcp-studio";
    public static final String SERVER_VERSION = "1.0.0";

    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    private static final int JSONRPC_PARSE_ERROR = -32700;
    private static final int JSONRPC_INVALID_REQUEST = -32600;
    private static final int JSONRPC_METHOD_NOT_FOUND = -32601;
    private static final int JSONRPC_INVALID_PARAMS = -32602;
    private static final int JSONRPC_INTERNAL_ERROR = -32603;

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 50;
    private static final long SSE_KEEPALIVE_SECONDS = 15L;

    private final SearchService searchService;
    private final ObjectMapper objectMapper;

    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Set<CountDownLatch> activeSseLatches = ConcurrentHashMap.newKeySet();

    private HttpServer httpServer;
    private ExecutorService executor;
    private volatile String activeDbPath;
    private volatile boolean running;
    private volatile McpServerStatus currentStatus = McpServerStatus.stopped();

    public DynamicMcpServerAdapter(SearchService searchService, ObjectMapper objectMapper) {
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public synchronized McpServerStatus start(int port, String dbPath) {
        if (running) {
            throw new IllegalStateException("MCP server is already running on port " + currentStatus.port());
        }

        if (port < 0 || port > 65535) {
            String error = "Port must be between 0 and 65535, got: " + port;
            currentStatus = McpServerStatus.stopped(error);
            throw new IllegalArgumentException(error);
        }
        if (dbPath == null || dbPath.isBlank()) {
            String error = "dbPath must not be blank";
            currentStatus = McpServerStatus.stopped(error);
            throw new IllegalArgumentException(error);
        }

        DatabaseMetadata metadata;
        try {
            metadata = searchService.inspectDatabase(dbPath);
            if (metadata == null) {
                throw new IllegalStateException("Database metadata not found for: " + dbPath);
            }
        } catch (RuntimeException e) {
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            currentStatus = McpServerStatus.stopped(error);
            throw e;
        }

        HttpServer server = null;
        ExecutorService exec = null;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            exec = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(exec);
            server.createContext(MCP_PATH, this::handleMcpExchange);

            this.activeDbPath = dbPath;
            this.httpServer = server;
            this.executor = exec;
            this.running = true;

            server.start();

            int boundPort = server.getAddress().getPort();
            String endpointUrl = "http://localhost:" + boundPort + MCP_PATH;
            this.currentStatus = new McpServerStatus(true, boundPort, endpointUrl, dbPath, metadata, null);
            return this.currentStatus;
        } catch (IOException | RuntimeException e) {
            this.running = false;
            this.activeDbPath = null;
            this.httpServer = null;
            this.executor = null;
            if (server != null) {
                try {
                    server.stop(0);
                } catch (RuntimeException ignored) {
                    // best-effort cleanup
                }
            }
            if (exec != null) {
                exec.shutdownNow();
            }
            String error = "Failed to start MCP server on port " + port + ": " + e.getMessage();
            this.currentStatus = McpServerStatus.stopped(error);
            throw new IllegalStateException(error, e);
        }
    }

    @Override
    public synchronized McpServerStatus stop() {
        this.running = false;

        for (CountDownLatch latch : activeSseLatches) {
            latch.countDown();
        }
        activeSseLatches.clear();
        activeSessions.clear();

        if (httpServer != null) {
            try {
                httpServer.stop(0);
            } finally {
                httpServer = null;
            }
        }

        if (executor != null) {
            try {
                executor.shutdownNow();
            } finally {
                executor = null;
            }
        }

        this.activeDbPath = null;
        this.currentStatus = McpServerStatus.stopped();
        return this.currentStatus;
    }

    @Override
    public synchronized McpServerStatus restart(int port, String dbPath) {
        if (running || httpServer != null) {
            stop();
        }
        return start(port, dbPath);
    }

    @Override
    public McpServerStatus status() {
        return currentStatus;
    }

    @Override
    public void close() {
        stop();
    }

    private void handleMcpExchange(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange.getResponseHeaders());
        String method = exchange.getRequestMethod();

        try {
            switch (method.toUpperCase(Locale.ROOT)) {
                case "OPTIONS" -> {
                    exchange.sendResponseHeaders(204, -1);
                }
                case "POST" -> handlePost(exchange);
                case "GET" -> handleGetSse(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE, OPTIONS");
                    sendJsonResponse(exchange, 405, createErrorResponse(null, JSONRPC_INVALID_REQUEST,
                            "Method not allowed: " + method), false);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            sendJsonResponse(exchange, 500, createErrorResponse(null, JSONRPC_INTERNAL_ERROR,
                    "Internal server error: " + e.getMessage()), false);
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        boolean ssePreferred = prefersEventStreamOnly(exchange.getRequestHeaders().getFirst("Accept"));

        JsonNode requestNode;
        try {
            if (body == null || body.isBlank()) {
                sendJsonResponse(exchange, 400, createErrorResponse(null, JSONRPC_PARSE_ERROR,
                        "Empty request body"), ssePreferred);
                return;
            }
            requestNode = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            sendJsonResponse(exchange, 400, createErrorResponse(null, JSONRPC_PARSE_ERROR,
                    "Parse error: " + e.getOriginalMessage()), ssePreferred);
            return;
        }

        if (requestNode == null || !requestNode.isObject()) {
            sendJsonResponse(exchange, 400, createErrorResponse(null, JSONRPC_INVALID_REQUEST,
                    "Invalid JSON-RPC request: expected a JSON object"), ssePreferred);
            return;
        }

        JsonNode methodNode = requestNode.get("method");
        if (methodNode == null || !methodNode.isTextual() || methodNode.asText().isBlank()) {
            JsonNode idNode = requestNode.get("id");
            sendJsonResponse(exchange, 400, createErrorResponse(idNode, JSONRPC_INVALID_REQUEST,
                    "Invalid JSON-RPC request: missing 'method'"), ssePreferred);
            return;
        }

        String rpcMethod = methodNode.asText();
        boolean hasId = requestNode.has("id") && !requestNode.get("id").isNull();
        JsonNode idNode = hasId ? requestNode.get("id") : null;
        JsonNode paramsNode = requestNode.get("params");

        // JSON-RPC notifications (no id) -> 202 Accepted with empty body
        if (!hasId) {
            echoSessionHeaderIfPresent(exchange);
            exchange.sendResponseHeaders(202, -1);
            return;
        }

        ObjectNode response;
        switch (rpcMethod) {
            case "initialize" -> {
                String sessionId = UUID.randomUUID().toString();
                activeSessions.add(sessionId);
                exchange.getResponseHeaders().set(HEADER_SESSION_ID, sessionId);
                response = handleInitialize(idNode, paramsNode);
            }
            case "ping" -> {
                echoSessionHeaderIfPresent(exchange);
                response = createSuccessResponse(idNode, objectMapper.createObjectNode());
            }
            case "tools/list" -> {
                echoSessionHeaderIfPresent(exchange);
                response = handleToolsList(idNode);
            }
            case "tools/call" -> {
                echoSessionHeaderIfPresent(exchange);
                response = handleToolsCall(idNode, paramsNode);
            }
            default -> {
                echoSessionHeaderIfPresent(exchange);
                response = createErrorResponse(idNode, JSONRPC_METHOD_NOT_FOUND,
                        "Method not found: " + rpcMethod);
            }
        }

        sendJsonResponse(exchange, 200, response, ssePreferred);
    }

    private void handleGetSse(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        echoSessionHeaderIfPresent(exchange);

        exchange.sendResponseHeaders(200, 0);

        CountDownLatch latch = new CountDownLatch(1);
        activeSseLatches.add(latch);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            while (running && !Thread.currentThread().isInterrupted()) {
                boolean stopped = latch.await(SSE_KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                if (stopped || !running) {
                    break;
                }
                os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // client closed SSE connection
        } finally {
            activeSseLatches.remove(latch);
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        String sessionId = exchange.getRequestHeaders().getFirst(HEADER_SESSION_ID);
        if (sessionId != null && !sessionId.isBlank()) {
            activeSessions.remove(sessionId);
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private ObjectNode handleInitialize(JsonNode idNode, JsonNode paramsNode) {
        String protocolVersion = DEFAULT_PROTOCOL_VERSION;
        if (paramsNode != null && paramsNode.hasNonNull("protocolVersion")) {
            String requested = paramsNode.get("protocolVersion").asText();
            if (requested != null && !requested.isBlank()) {
                protocolVersion = requested;
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", protocolVersion);

        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.set("tools", objectMapper.createObjectNode());
        result.set("capabilities", capabilities);

        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);

        return createSuccessResponse(idNode, result);
    }

    private ObjectNode handleToolsList(JsonNode idNode) {
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(buildSearchDocsToolSchema());
        tools.add(buildListIndexedSourcesToolSchema());

        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        return createSuccessResponse(idNode, result);
    }

    private ObjectNode buildSearchDocsToolSchema() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "search_docs");
        tool.put("description",
                "Hybrid semantic + FTS5 full-text search over the active local SQLite RAG database.");

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode queryProp = objectMapper.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", "Search query text or keywords.");
        properties.set("query", queryProp);

        ObjectNode topKProp = objectMapper.createObjectNode();
        topKProp.put("type", "integer");
        topKProp.put("description", "Maximum number of ranked chunks to return.");
        topKProp.put("default", SearchService.DEFAULT_TOP_K);
        topKProp.put("minimum", MIN_TOP_K);
        topKProp.put("maximum", MAX_TOP_K);
        properties.set("top_k", topKProp);

        inputSchema.set("properties", properties);

        ArrayNode required = objectMapper.createArrayNode();
        required.add("query");
        inputSchema.set("required", required);
        inputSchema.put("additionalProperties", false);

        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private ObjectNode buildListIndexedSourcesToolSchema() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "list_indexed_sources");
        tool.put("description",
                "Lists all indexed documents/sources and their chunk counts in the active local SQLite RAG database.");

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", objectMapper.createObjectNode());
        inputSchema.put("additionalProperties", false);

        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private ObjectNode handleToolsCall(JsonNode idNode, JsonNode paramsNode) {
        if (paramsNode == null || !paramsNode.isObject()) {
            return createErrorResponse(idNode, JSONRPC_INVALID_PARAMS, "Missing or invalid 'params' object");
        }

        JsonNode nameNode = paramsNode.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            return createErrorResponse(idNode, JSONRPC_INVALID_PARAMS, "Missing tool 'name'");
        }

        String toolName = nameNode.asText();
        JsonNode arguments = paramsNode.get("arguments");

        return switch (toolName) {
            case "search_docs" -> executeSearchDocs(idNode, arguments);
            case "list_indexed_sources" -> executeListIndexedSources(idNode);
            default -> createErrorResponse(idNode, JSONRPC_INVALID_PARAMS, "Unknown tool: " + toolName);
        };
    }

    private ObjectNode executeSearchDocs(JsonNode idNode, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return createToolResult(idNode, "Parameter 'query' is required and must not be blank.", true);
        }

        JsonNode queryNode = arguments.get("query");
        if (queryNode == null || !queryNode.isTextual() || queryNode.asText().isBlank()) {
            return createToolResult(idNode, "Parameter 'query' is required and must not be blank.", true);
        }
        String query = queryNode.asText().trim();

        int topK = SearchService.DEFAULT_TOP_K;
        if (arguments.has("top_k") && !arguments.get("top_k").isNull()) {
            JsonNode topKNode = arguments.get("top_k");
            if (!topKNode.canConvertToInt()) {
                return createToolResult(idNode, "Parameter 'top_k' must be an integer between 1 and 50.", true);
            }
            topK = topKNode.asInt();
            if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
                return createToolResult(idNode, "Parameter 'top_k' must be between 1 and 50.", true);
            }
        }

        String dbPath = this.activeDbPath;
        if (dbPath == null) {
            return createToolResult(idNode, "MCP server has no active database configured.", true);
        }

        try {
            List<SearchResult> results = searchService.search(dbPath, query, topK);
            String formatted = formatSearchResults(query, results);
            return createToolResult(idNode, formatted, false);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return createToolResult(idNode, "Search failed: " + message, true);
        }
    }

    private ObjectNode executeListIndexedSources(JsonNode idNode) {
        String dbPath = this.activeDbPath;
        if (dbPath == null) {
            return createToolResult(idNode, "MCP server has no active database configured.", true);
        }

        try {
            List<SourceSummary> sources = searchService.listIndexedSources(dbPath);
            String formatted = formatIndexedSources(sources);
            return createToolResult(idNode, formatted, false);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return createToolResult(idNode, "Failed to list indexed sources: " + message, true);
        }
    }

    private String formatSearchResults(String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "No matching documents found for query: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" result(s) for query: \"").append(query).append("\"\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("\n[").append(i + 1).append("] Source: ").append(r.source());
            if (r.title() != null && !r.title().isBlank()) {
                sb.append(" | Title: ").append(r.title());
            }
            sb.append('\n');
            sb.append(String.format(Locale.ROOT,
                    "Score: %.4f (vector: %.4f, fts: %.4f)%n",
                    r.score(), r.vectorScore(), r.ftsScore()));
            sb.append("---\n");
            sb.append(r.content() != null ? r.content() : "").append('\n');
        }
        return sb.toString().trim();
    }

    private String formatIndexedSources(List<SourceSummary> sources) {
        if (sources == null || sources.isEmpty()) {
            return "No indexed sources found in the active database.";
        }

        long totalChunks = 0;
        for (SourceSummary summary : sources) {
            totalChunks += summary.chunkCount();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Indexed sources (").append(sources.size())
                .append(" documents, ").append(totalChunks).append(" chunks total):\n");
        for (SourceSummary summary : sources) {
            sb.append("- ").append(summary.source())
                    .append(" (").append(summary.chunkCount()).append(" chunks)\n");
        }
        return sb.toString().trim();
    }

    private ObjectNode createToolResult(JsonNode idNode, String text, boolean isError) {
        ObjectNode contentItem = objectMapper.createObjectNode();
        contentItem.put("type", "text");
        contentItem.put("text", text);

        ArrayNode contentArray = objectMapper.createArrayNode();
        contentArray.add(contentItem);

        ObjectNode result = objectMapper.createObjectNode();
        result.set("content", contentArray);
        result.put("isError", isError);

        return createSuccessResponse(idNode, result);
    }

    private ObjectNode createSuccessResponse(JsonNode idNode, JsonNode resultNode) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (idNode != null) {
            response.set("id", idNode);
        } else {
            response.putNull("id");
        }
        response.set("result", resultNode);
        return response;
    }

    private ObjectNode createErrorResponse(JsonNode idNode, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (idNode != null) {
            response.set("id", idNode);
        } else {
            response.putNull("id");
        }
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message != null ? message : "Unknown error");
        response.set("error", error);
        return response;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, ObjectNode payload, boolean sseFormat)
            throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        byte[] bytes;
        if (sseFormat) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            bytes = ("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            bytes = json.getBytes(StandardCharsets.UTF_8);
        }
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void echoSessionHeaderIfPresent(HttpExchange exchange) {
        String sessionId = exchange.getRequestHeaders().getFirst(HEADER_SESSION_ID);
        if (sessionId != null && !sessionId.isBlank()) {
            exchange.getResponseHeaders().set(HEADER_SESSION_ID, sessionId);
        }
    }

    private boolean prefersEventStreamOnly(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        String lower = acceptHeader.toLowerCase(Locale.ROOT);
        boolean acceptsSse = lower.contains("text/event-stream");
        boolean acceptsJson = lower.contains("application/json") || lower.contains("*/*");
        return acceptsSse && !acceptsJson;
    }

    private void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers",
                "Content-Type, Accept, Mcp-Session-Id, Last-Event-ID, MCP-Protocol-Version");
        headers.set("Access-Control-Expose-Headers", "Mcp-Session-Id, MCP-Protocol-Version");
    }
}
