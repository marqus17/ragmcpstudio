package com.ragmcp.studio.adapter.in.web.dto;

public record SearchTestRequest(
        String dbPath,
        String query,
        Integer topK
) {
}
