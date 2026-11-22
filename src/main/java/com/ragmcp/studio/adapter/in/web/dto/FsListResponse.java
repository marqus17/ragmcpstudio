package com.ragmcp.studio.adapter.in.web.dto;

import java.util.List;

public record FsListResponse(
        String currentPath,
        String parentPath,
        List<FsEntry> entries
) {
}
