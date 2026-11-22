package com.ragmcp.studio.adapter.in.web.dto;

public record FsEntry(
        String name,
        String path,
        boolean directory,
        long sizeBytes
) {
}
