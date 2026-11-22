package com.ragmcp.studio.adapter.in.web;

import com.ragmcp.studio.adapter.in.web.dto.IngestionProgressResponse;
import com.ragmcp.studio.adapter.in.web.dto.StartIngestionRequest;
import com.ragmcp.studio.application.IngestionService;
import com.ragmcp.studio.domain.model.IngestionConfig;
import com.ragmcp.studio.domain.model.IngestionProgress;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService must not be null");
    }

    @PostMapping("/start")
    public ResponseEntity<IngestionProgressResponse> start(@RequestBody StartIngestionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body must not be null");
        }
        int chunkSize = request.chunkSize() != null ? request.chunkSize() : IngestionConfig.DEFAULT_CHUNK_SIZE;
        int chunkOverlap = request.chunkOverlap() != null ? request.chunkOverlap() : IngestionConfig.DEFAULT_CHUNK_OVERLAP;

        IngestionConfig config = new IngestionConfig(
                request.sourceDir(),
                request.dbPath(),
                chunkSize,
                chunkOverlap
        );

        IngestionProgress progress = ingestionService.startIngestion(config);
        return ResponseEntity.accepted().body(IngestionProgressResponse.from(progress));
    }

    @PostMapping("/cancel")
    public ResponseEntity<IngestionProgressResponse> cancel() {
        ingestionService.cancel();
        return ResponseEntity.ok(IngestionProgressResponse.from(ingestionService.getProgress()));
    }

    @GetMapping("/status")
    public IngestionProgressResponse status() {
        return IngestionProgressResponse.from(ingestionService.getProgress());
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);

        Consumer<IngestionProgress> listener = new Consumer<>() {
            @Override
            public void accept(IngestionProgress progress) {
                if (closed.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(IngestionProgressResponse.from(progress), MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    if (closed.compareAndSet(false, true)) {
                        ingestionService.removeProgressListener(this);
                        emitter.complete();
                    }
                }
            }
        };

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                ingestionService.removeProgressListener(listener);
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(ignored -> cleanup.run());

        ingestionService.addProgressListener(listener);

        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(IngestionProgressResponse.from(ingestionService.getProgress()), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            cleanup.run();
            emitter.complete();
        }

        return emitter;
    }
}
