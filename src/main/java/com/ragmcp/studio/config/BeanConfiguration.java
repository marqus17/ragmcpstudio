package com.ragmcp.studio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragmcp.studio.adapter.in.mcp.DynamicMcpServerAdapter;
import com.ragmcp.studio.adapter.out.document.FilesystemDocumentExtractor;
import com.ragmcp.studio.adapter.out.embedding.EmbeddedOnnxEmbeddingAdapter;
import com.ragmcp.studio.adapter.out.sqlite.SqliteVectorStoreAdapter;
import com.ragmcp.studio.application.IngestionService;
import com.ragmcp.studio.application.SearchService;
import com.ragmcp.studio.domain.port.DocumentExtractorPort;
import com.ragmcp.studio.domain.port.EmbeddingPort;
import com.ragmcp.studio.domain.port.McpServerControllerPort;
import com.ragmcp.studio.domain.port.VectorStorePort;
import com.ragmcp.studio.domain.service.DocumentChunker;
import com.ragmcp.studio.domain.service.RrfFusion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfiguration {

    @Bean
    public VectorStorePort vectorStorePort() {
        return new SqliteVectorStoreAdapter();
    }

    @Bean
    public EmbeddingPort embeddingPort() {
        return new EmbeddedOnnxEmbeddingAdapter();
    }

    @Bean
    public DocumentExtractorPort documentExtractorPort() {
        return new FilesystemDocumentExtractor();
    }

    @Bean
    public DocumentChunker documentChunker() {
        return new DocumentChunker();
    }

    @Bean
    public RrfFusion rrfFusion() {
        return new RrfFusion();
    }

    @Bean(destroyMethod = "close")
    public IngestionService ingestionService(
            DocumentExtractorPort documentExtractorPort,
            DocumentChunker documentChunker,
            EmbeddingPort embeddingPort,
            VectorStorePort vectorStorePort
    ) {
        return new IngestionService(documentExtractorPort, documentChunker, embeddingPort, vectorStorePort);
    }

    @Bean
    public SearchService searchService(
            EmbeddingPort embeddingPort,
            VectorStorePort vectorStorePort,
            RrfFusion rrfFusion
    ) {
        return new SearchService(embeddingPort, vectorStorePort, rrfFusion);
    }

    @Bean(destroyMethod = "stop")
    public McpServerControllerPort mcpServerControllerPort(
            SearchService searchService,
            ObjectMapper objectMapper
    ) {
        return new DynamicMcpServerAdapter(searchService, objectMapper);
    }
}
