package com.ragmcp.studio.domain.model;

import java.util.List;

public sealed interface IngestionProgress
        permits IngestionProgress.Idle,
                IngestionProgress.Running,
                IngestionProgress.Completed,
                IngestionProgress.Cancelled,
                IngestionProgress.Failed {

    default List<String> logs() {
        return List.of();
    }

    default String state() {
        return switch (this) {
            case Idle ignored -> "IDLE";
            case Running ignored -> "RUNNING";
            case Completed ignored -> "COMPLETED";
            case Cancelled ignored -> "CANCELLED";
            case Failed ignored -> "FAILED";
        };
    }

    record Idle() implements IngestionProgress {
    }

    record Running(
            int processedFiles,
            int totalFiles,
            int totalChunks,
            String currentFile,
            List<String> logs
    ) implements IngestionProgress {
        public Running {
            currentFile = currentFile == null ? "" : currentFile;
            logs = logs == null ? List.of() : List.copyOf(logs);
        }
    }

    record Completed(
            int processedFiles,
            int totalChunks,
            long durationMs,
            List<String> logs
    ) implements IngestionProgress {
        public Completed {
            logs = logs == null ? List.of() : List.copyOf(logs);
        }
    }

    record Cancelled(
            int processedFiles,
            int totalFiles,
            List<String> logs
    ) implements IngestionProgress {
        public Cancelled {
            logs = logs == null ? List.of() : List.copyOf(logs);
        }
    }

    record Failed(
            String error,
            List<String> logs
    ) implements IngestionProgress {
        public Failed {
            error = error == null ? "Unknown error" : error;
            logs = logs == null ? List.of() : List.copyOf(logs);
        }
    }
}
