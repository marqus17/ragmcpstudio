package com.ragmcp.studio.adapter.in.web.dto;

public record McpStartRequest(
        Integer port,
        String dbPath
) {
}
