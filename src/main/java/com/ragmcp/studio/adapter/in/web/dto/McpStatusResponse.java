package com.ragmcp.studio.adapter.in.web.dto;

import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.McpServerStatus;

public record McpStatusResponse(
        boolean running,
        int port,
        String endpointUrl,
        String activeDbPath,
        DatabaseMetadata metadata,
        String lastError,
        String opencodeConfig
) {
    private static final String DEFAULT_ENDPOINT_URL = "http://localhost:8081/mcp";

    public static McpStatusResponse from(McpServerStatus status) {
        McpServerStatus safeStatus = status != null ? status : McpServerStatus.stopped();
        String urlForSnippet = (safeStatus.endpointUrl() != null && !safeStatus.endpointUrl().isBlank())
                ? safeStatus.endpointUrl()
                : DEFAULT_ENDPOINT_URL;

        return new McpStatusResponse(
                safeStatus.running(),
                safeStatus.port(),
                safeStatus.endpointUrl(),
                safeStatus.activeDbPath(),
                safeStatus.metadata(),
                safeStatus.lastError(),
                buildOpencodeSnippet(urlForSnippet)
        );
    }

    public static String buildOpencodeSnippet(String endpointUrl) {
        String safeUrl = (endpointUrl == null || endpointUrl.isBlank()) ? DEFAULT_ENDPOINT_URL : endpointUrl;
        return """
                {
                  "$schema": "https://opencode.ai/config.json",
                  "mcp": {
                    "local-rag": {
                      "type": "remote",
                      "url": "%s",
                      "enabled": true
                    }
                  }
                }""".formatted(safeUrl);
    }
}
