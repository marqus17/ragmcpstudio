package com.ragmcp.studio.domain;

import com.ragmcp.studio.domain.model.ScoredChunk;
import com.ragmcp.studio.domain.model.SearchResult;
import com.ragmcp.studio.domain.service.RrfFusion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    @Test
    void boostsChunksAppearingInBothVectorAndFtsResults() {
        ScoredChunk c1 = new ScoredChunk(1L, "a.md", "A", "vector top only", 0.95);
        ScoredChunk c2Vector = new ScoredChunk(2L, "b.md", "B", "strong in both", 0.88);
        ScoredChunk c2Fts = new ScoredChunk(2L, "b.md", "B", "strong in both", 12.5);
        ScoredChunk c3 = new ScoredChunk(3L, "c.md", "C", "fts top only", 15.0);

        List<ScoredChunk> vector = List.of(c1, c2Vector);
        List<ScoredChunk> fts = List.of(c3, c2Fts);

        List<SearchResult> results = fusion.fuse(vector, fts, 5);

        assertThat(results).hasSize(3);
        // Chunk 2 appears at rank 2 in both lists: 2 / (60 + 2) > 1 / (60 + 1)
        assertThat(results.get(0).chunkId()).isEqualTo(2L);
        assertThat(results.get(0).score()).isCloseTo(2.0 / 62.0, within(1e-12));
        assertThat(results.get(0).vectorScore()).isEqualTo(0.88);
        assertThat(results.get(0).ftsScore()).isEqualTo(12.5);

        // Chunks 1 and 3 each have 1 / 61
        assertThat(results.get(1).score()).isCloseTo(1.0 / 61.0, within(1e-12));
        assertThat(results.get(2).score()).isCloseTo(1.0 / 61.0, within(1e-12));
    }

    @Test
    void respectsTopKSlicingAndHandlesEmptyInputs() {
        List<ScoredChunk> vector = List.of(
                new ScoredChunk(10L, "s1", "t1", "content1", 0.9),
                new ScoredChunk(20L, "s2", "t2", "content2", 0.8),
                new ScoredChunk(30L, "s3", "t3", "content3", 0.7)
        );

        List<SearchResult> top2 = fusion.fuse(vector, List.of(), 2);
        assertThat(top2).extracting(SearchResult::chunkId).containsExactly(10L, 20L);

        assertThat(fusion.fuse(null, null, 5)).isEmpty();
        assertThat(fusion.fuse(vector, List.of(), 0)).isEmpty();
    }
}
