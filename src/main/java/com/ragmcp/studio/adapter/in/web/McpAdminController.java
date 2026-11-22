package com.ragmcp.studio.adapter.in.web;

import com.ragmcp.studio.adapter.in.web.dto.DbInspectResponse;
import com.ragmcp.studio.adapter.in.web.dto.FsEntry;
import com.ragmcp.studio.adapter.in.web.dto.FsListResponse;
import com.ragmcp.studio.adapter.in.web.dto.McpStartRequest;
import com.ragmcp.studio.adapter.in.web.dto.McpStatusResponse;
import com.ragmcp.studio.adapter.in.web.dto.SearchTestRequest;
import com.ragmcp.studio.application.SearchService;
import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.McpServerStatus;
import com.ragmcp.studio.domain.model.SearchResult;
import com.ragmcp.studio.domain.model.SourceSummary;
import com.ragmcp.studio.domain.port.McpServerControllerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class McpAdminController {

    private static final int DEFAULT_MCP_PORT = 8081;

    private final McpServerControllerPort mcpServerController;
    private final SearchService searchService;

    public McpAdminController(
            McpServerControllerPort mcpServerController,
            SearchService searchService
    ) {
        this.mcpServerController = Objects.requireNonNull(mcpServerController, "mcpServerController must not be null");
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
    }

    @GetMapping("/db/inspect")
    public DbInspectResponse inspectDatabase(@RequestParam("path") String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path dbFile = Path.of(path.trim()).toAbsolutePath().normalize();
        if (!Files.exists(dbFile) || !Files.isRegularFile(dbFile)) {
            throw new NoSuchFileException("Database file not found: " + dbFile);
        }

        DatabaseMetadata metadata = searchService.inspectDatabase(dbFile.toString());
        List<SourceSummary> sources = searchService.listIndexedSources(dbFile.toString());
        return new DbInspectResponse(dbFile.toString(), metadata, sources);
    }

    @GetMapping("/mcp/status")
    public McpStatusResponse mcpStatus() {
        return McpStatusResponse.from(mcpServerController.status());
    }

    @PostMapping("/mcp/start")
    public McpStatusResponse startMcp(@RequestBody McpStartRequest request) {
        ValidatedMcpParams params = validateMcpParams(request);
        McpServerStatus status = mcpServerController.start(params.port(), params.dbPath());
        return McpStatusResponse.from(status);
    }

    @PostMapping("/mcp/stop")
    public McpStatusResponse stopMcp() {
        return McpStatusResponse.from(mcpServerController.stop());
    }

    @PostMapping("/mcp/restart")
    public McpStatusResponse restartMcp(@RequestBody McpStartRequest request) {
        ValidatedMcpParams params = validateMcpParams(request);
        McpServerStatus status = mcpServerController.restart(params.port(), params.dbPath());
        return McpStatusResponse.from(status);
    }

    @PostMapping("/search/test")
    public List<SearchResult> testSearch(@RequestBody SearchTestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body must not be null");
        }
        int topK = request.topK() != null ? request.topK() : SearchService.DEFAULT_TOP_K;

        return searchService.search(request.dbPath(), request.query(), topK);
    }

    @GetMapping("/fs/list")
    public FsListResponse listFilesystem(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "mode", defaultValue = "dir") String mode
    ) throws IOException {
        Path target = (path == null || path.isBlank())
                ? Path.of(System.getProperty("user.home"))
                : Path.of(path.trim());
        target = target.toAbsolutePath().normalize();

        if (!Files.exists(target)) {
            throw new NoSuchFileException("Path does not exist: " + target);
        }
        if (Files.isRegularFile(target)) {
            Path parent = target.getParent();
            target = parent != null ? parent : target;
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("Path is not a directory: " + target);
        }

        boolean includeDbFiles = "db".equalsIgnoreCase(mode);
        List<FsEntry> entries;
        try (Stream<Path> stream = Files.list(target)) {
            entries = stream
                    .filter(p -> isAllowedEntry(p, includeDbFiles))
                    .map(McpAdminController::toFsEntry)
                    .filter(Objects::nonNull)
                    .sorted(Comparator
                            .comparing(FsEntry::directory).reversed()
                            .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)))
                    .toList();
        }

        Path parent = target.getParent();
        return new FsListResponse(
                target.toString(),
                parent != null ? parent.toString() : null,
                entries
        );
    }

    private static ValidatedMcpParams validateMcpParams(McpStartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body must not be null");
        }
        int port = request.port() != null ? request.port() : DEFAULT_MCP_PORT;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (request.dbPath() == null || request.dbPath().isBlank()) {
            throw new IllegalArgumentException("dbPath must not be blank");
        }
        return new ValidatedMcpParams(port, request.dbPath().trim());
    }

    private static boolean isAllowedEntry(Path path, boolean includeDbFiles) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        if (name.startsWith(".")) {
            return false;
        }
        if (Files.isDirectory(path)) {
            return true;
        }
        if (includeDbFiles && Files.isRegularFile(path)) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3");
        }
        return false;
    }

    private static FsEntry toFsEntry(Path path) {
        try {
            boolean dir = Files.isDirectory(path);
            long size = dir ? 0L : Files.size(path);
            return new FsEntry(path.getFileName().toString(), path.toAbsolutePath().normalize().toString(), dir, size);
        } catch (IOException e) {
            return null;
        }
    }

    private record ValidatedMcpParams(int port, String dbPath) {
    }
}
