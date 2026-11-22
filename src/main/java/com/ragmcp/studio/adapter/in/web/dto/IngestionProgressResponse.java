package com.ragmcp.studio.adapter.in.web.dto;

import com.ragmcp.studio.domain.model.IngestionProgress;

import java.util.List;

public record IngestionProgressResponse(
        String state,
        int processedFiles,
        int totalFiles,
        int totalChunks,
        String currentFile,
        long durationMs,
        String error,
        List<String> logs
) {
    public static IngestionProgressResponse from(IngestionProgress progress) {
        if (progress == null) {
            return new IngestionProgressResponse("IDLE", 0, 0, 0, "", 0L, null, List.of());
        }
        return switch (progress) {
            case IngestionProgress.Idle ignored ->
                    new IngestionProgressResponse("IDLE", 0, 0, 0, "", 0L, null, List.of());
            case IngestionProgress.Running r ->
                    new IngestionProgressResponse(
                            "RUNNING",
                            r.processedFiles(),
                            r.totalFiles(),
                            r.totalChunks(),
                            r.currentFile(),
                            0L,
                            null,
                            r.logs()
                    );
            case IngestionProgress.Completed c ->
                    new IngestionProgressResponse(
                            "COMPLETED",
                            c.processedFiles(),
                            c.processedFiles(),
                            c.totalChunks(),
                            "",
                            c.durationMs(),
                            null,
                            c.logs()
                    );
            case IngestionProgress.Cancelled c ->
                    new IngestionProgressResponse(
                            "CANCELLED",
                            c.processedFiles(),
                            c.totalFiles(),
                            0,
                            "",
                            0L,
                            null,
                            c.logs()
                    );
            case IngestionProgress.Failed f ->
                    new IngestionProgressResponse(
                            "FAILED",
                            0,
                            0,
                            0,
                            "",
                            0L,
                            f.error(),
                            f.logs()
                    );
        };
    }
}
