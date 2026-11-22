package com.ragmcp.studio.domain.model;

public record McpServerStatus(
        boolean running,
        int port,
        String endpointUrl,
        String activeDbPath,
        DatabaseMetadata metadata,
        String lastError
) {
    public static McpServerStatus stopped() {
        return new McpServerStatus(false, 0, null, null, null, null);
    }

    public static McpServerStatus stopped(String lastError) {
        return new McpServerStatus(false, 0, null, null, null, lastError);
    }
}
