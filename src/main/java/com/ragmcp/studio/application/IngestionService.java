package com.ragmcp.studio.application;

import com.ragmcp.studio.domain.model.Chunk;
import com.ragmcp.studio.domain.model.DocumentContent;
import com.ragmcp.studio.domain.model.IngestionConfig;
import com.ragmcp.studio.domain.model.IngestionProgress;
import com.ragmcp.studio.domain.port.DocumentExtractorPort;
import com.ragmcp.studio.domain.port.EmbeddingPort;
import com.ragmcp.studio.domain.port.VectorStorePort;
import com.ragmcp.studio.domain.service.DocumentChunker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class IngestionService implements AutoCloseable {

    private static final int MAX_LOG_ENTRIES = 500;
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DocumentExtractorPort documentExtractor;
    private final DocumentChunker documentChunker;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStore;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<IngestionProgress> progress = new AtomicReference<>(new IngestionProgress.Idle());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Consumer<IngestionProgress>> listeners = new CopyOnWriteArrayList<>();

    public IngestionService(
            DocumentExtractorPort documentExtractor,
            DocumentChunker documentChunker,
            EmbeddingPort embeddingPort,
            VectorStorePort vectorStore
    ) {
        this.documentExtractor = Objects.requireNonNull(documentExtractor, "documentExtractor must not be null");
        this.documentChunker = Objects.requireNonNull(documentChunker, "documentChunker must not be null");
        this.embeddingPort = Objects.requireNonNull(embeddingPort, "embeddingPort must not be null");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
    }

    public IngestionProgress startIngestion(IngestionConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("An ingestion job is already running");
        }

        cancelRequested.set(false);
        List<String> initialLogs = new ArrayList<>();
        appendLog(initialLogs, "Starting ingestion from " + config.sourceDir() + " -> " + config.dbPath());

        IngestionProgress initial = new IngestionProgress.Running(0, 0, 0, "", initialLogs);
        publish(initial);

        executor.submit(() -> executeIngestion(config, initialLogs));
        return initial;
    }

    public void cancel() {
        cancelRequested.set(true);
    }

    public boolean isRunning() {
        return running.get();
    }

    public IngestionProgress getProgress() {
        return progress.get();
    }

    public AutoCloseable addProgressListener(Consumer<IngestionProgress> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void removeProgressListener(Consumer<IngestionProgress> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void executeIngestion(IngestionConfig config, List<String> logs) {
        long startNanos = System.nanoTime();
        int processedFiles = 0;
        int totalFiles = 0;
        int totalChunks = 0;

        try {
            Path rootDir = Path.of(config.sourceDir());
            if (!Files.exists(rootDir) || !Files.isDirectory(rootDir)) {
                throw new IllegalArgumentException("Source directory does not exist or is not a directory: " + rootDir);
            }

            List<Path> files = documentExtractor.listSupportedFiles(rootDir);
            totalFiles = files.size();
            appendLog(logs, "Discovered " + totalFiles + " supported file(s)");
            publish(new IngestionProgress.Running(0, totalFiles, 0, "", logs));

            if (cancelRequested.get()) {
                appendLog(logs, "Ingestion cancelled before embedding probe");
                publish(new IngestionProgress.Cancelled(0, totalFiles, logs));
                return;
            }

            String embedModel = embeddingPort.modelName();
            int dimensions = embeddingPort.dimensions();
            appendLog(logs, "Using embedded ONNX model '" + embedModel + "' (" + dimensions + " dimensions)");

            vectorStore.initializeDatabase(config.dbPath(), embedModel, dimensions);
            appendLog(logs, "Initialized SQLite vector store at " + config.dbPath());
            publish(new IngestionProgress.Running(0, totalFiles, 0, "", logs));

            for (Path file : files) {
                if (cancelRequested.get()) {
                    vectorStore.evictCache(config.dbPath());
                    appendLog(logs, "Ingestion cancelled after " + processedFiles + "/" + totalFiles + " file(s)");
                    publish(new IngestionProgress.Cancelled(processedFiles, totalFiles, logs));
                    return;
                }

                String displayPath = relativizeSafe(rootDir, file);
                publish(new IngestionProgress.Running(processedFiles, totalFiles, totalChunks, displayPath, logs));

                try {
                    DocumentContent document = documentExtractor.extract(rootDir, file);
                    List<Chunk> chunks = documentChunker.chunk(document, config.chunkSize(), config.chunkOverlap());

                    if (chunks.isEmpty()) {
                        appendLog(logs, "Skipped empty file: " + displayPath);
                        processedFiles++;
                        publish(new IngestionProgress.Running(processedFiles, totalFiles, totalChunks, displayPath, logs));
                        continue;
                    }

                    List<Chunk> embeddedChunks = new ArrayList<>(chunks.size());
                    for (Chunk chunk : chunks) {
                        if (cancelRequested.get()) {
                            vectorStore.evictCache(config.dbPath());
                            appendLog(logs, "Ingestion cancelled during file: " + displayPath);
                            publish(new IngestionProgress.Cancelled(processedFiles, totalFiles, logs));
                            return;
                        }
                        float[] vector = embeddingPort.embedDocument(chunk.content());
                        embeddedChunks.add(chunk.withEmbedding(vector));
                    }

                    vectorStore.saveChunks(config.dbPath(), embeddedChunks);
                    totalChunks += embeddedChunks.size();
                    processedFiles++;
                    appendLog(logs, "Indexed " + displayPath + " (" + embeddedChunks.size() + " chunk(s))");
                    publish(new IngestionProgress.Running(processedFiles, totalFiles, totalChunks, displayPath, logs));
                } catch (Exception fileError) {
                    processedFiles++;
                    appendLog(logs, "ERROR indexing " + displayPath + ": " + safeMessage(fileError));
                    publish(new IngestionProgress.Running(processedFiles, totalFiles, totalChunks, displayPath, logs));
                }
            }

            vectorStore.evictCache(config.dbPath());
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            appendLog(logs, "Completed: " + processedFiles + " file(s), " + totalChunks + " chunk(s) in " + durationMs + " ms");
            publish(new IngestionProgress.Completed(processedFiles, totalChunks, durationMs, logs));
        } catch (Throwable error) {
            String message = safeMessage(error);
            appendLog(logs, "FAILED: " + message);
            publish(new IngestionProgress.Failed(message, logs));
        } finally {
            running.set(false);
        }
    }

    private void publish(IngestionProgress state) {
        progress.set(state);
        for (Consumer<IngestionProgress> listener : listeners) {
            try {
                listener.accept(state);
            } catch (Exception ignored) {
                // Keep ingestion resilient even if a subscriber fails
            }
        }
    }

    private static void appendLog(List<String> logs, String message) {
        String entry = "[" + LOG_TIME_FORMAT.format(Instant.now()) + "] " + message;
        logs.add(entry);
        while (logs.size() > MAX_LOG_ENTRIES) {
            logs.removeFirst();
        }
    }

    private static String relativizeSafe(Path rootDir, Path file) {
        try {
            return rootDir.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String msg = error.getMessage();
        return (msg == null || msg.isBlank()) ? error.getClass().getSimpleName() : msg;
    }

    @Override
    public void close() {
        cancelRequested.set(true);
        executor.shutdownNow();
    }
}
