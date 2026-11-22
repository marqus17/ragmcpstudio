package com.ragmcp.studio.domain;

import com.ragmcp.studio.domain.model.Chunk;
import com.ragmcp.studio.domain.model.DocumentContent;
import com.ragmcp.studio.domain.service.DocumentChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void returnsEmptyListForBlankDocument() {
        DocumentContent doc = new DocumentContent("empty.md", "Empty", "   \n\t  ");
        assertThat(chunker.chunk(doc)).isEmpty();
    }

    @Test
    void splitsMarkdownByHeadingsAndPreservesHeadingContext() {
        String markdown = """
                # Overview
                RAG MCP Studio is a local vectorization tool.

                ## Architecture
                The core follows hexagonal architecture with pure domain services.

                ## Search Engine
                Hybrid search combines cosine similarity with SQLite FTS5 using RRF.
                """;

        DocumentContent doc = new DocumentContent("docs/guide.md", "Guide", markdown);
        List<Chunk> chunks = chunker.chunk(doc, 500, 50);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).title()).isEqualTo("Guide > Overview");
        assertThat(chunks.get(0).content()).contains("# Overview").contains("local vectorization tool");

        assertThat(chunks.get(1).title()).isEqualTo("Guide > Architecture");
        assertThat(chunks.get(1).content()).contains("## Architecture").contains("hexagonal architecture");

        assertThat(chunks.get(2).title()).isEqualTo("Guide > Search Engine");
        assertThat(chunks.get(2).content()).contains("## Search Engine").contains("SQLite FTS5");
    }

    @Test
    void ignoresMarkdownHeadingsInsideFencedCodeBlocks() {
        String markdown = """
                ## Code Example
                Here is a bash snippet:
                ```bash
                # Not a markdown heading
                echo "hello"
                ```
                Done.
                """;

        DocumentContent doc = new DocumentContent("code.md", "Code", markdown);
        List<Chunk> chunks = chunker.chunk(doc, 500, 50);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().title()).isEqualTo("Code > Code Example");
        assertThat(chunks.getFirst().content()).contains("# Not a markdown heading");
    }

    @Test
    void splitsLongPlainTextWithOverlapAndRespectsChunkSize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("Paragraph number ").append(i)
                    .append(" contains enough descriptive words to test chunking and overlap behavior.")
                    .append("\n\n");
        }

        DocumentContent doc = new DocumentContent("notes.txt", "Notes", sb.toString());
        int chunkSize = 220;
        int chunkOverlap = 50;

        List<Chunk> chunks = chunker.chunk(doc, chunkSize, chunkOverlap);

        assertThat(chunks.size()).isGreaterThan(2);
        for (Chunk chunk : chunks) {
            assertThat(chunk.source()).isEqualTo("notes.txt");
            assertThat(chunk.title()).isEqualTo("Notes");
            assertThat(chunk.content().length()).isLessThanOrEqualTo(chunkSize);
        }

        // Verify consecutive chunks share overlapping text from the tail of the previous chunk
        String firstTail = chunks.get(0).content().substring(chunks.get(0).content().length() - 25).trim();
        assertThat(chunks.get(1).content()).contains(firstTail);
    }

    @Test
    void splitsUnbrokenLongTokenSafelyWithoutInfiniteLoop() {
        String longToken = "a".repeat(350);
        DocumentContent doc = new DocumentContent("token.txt", "Token", longToken);

        List<Chunk> chunks = chunker.chunk(doc, 100, 20);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(4);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(100));
    }

    @Test
    void rejectsInvalidChunkParameters() {
        DocumentContent doc = new DocumentContent("a.txt", "A", "hello");
        assertThatThrownBy(() -> chunker.chunk(doc, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.chunk(doc, 100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.chunk(doc, 100, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
