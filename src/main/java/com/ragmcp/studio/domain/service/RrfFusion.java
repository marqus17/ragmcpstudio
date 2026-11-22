package com.ragmcp.studio.domain.service;

import com.ragmcp.studio.domain.model.ScoredChunk;
import com.ragmcp.studio.domain.model.SearchResult;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    public List<SearchResult> fuse(List<ScoredChunk> vectorResults, List<ScoredChunk> ftsResults, int topK) {
        return fuse(vectorResults, ftsResults, topK, DEFAULT_K);
    }

    public List<SearchResult> fuse(List<ScoredChunk> vectorResults, List<ScoredChunk> ftsResults, int topK, int k) {
        if (topK < 0) {
            throw new IllegalArgumentException("topK must be >= 0");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        if (topK == 0) {
            return List.of();
        }

        Map<Long, MutableEntry> entries = new HashMap<>();

        if (vectorResults != null) {
            Set<Long> seenVector = new HashSet<>();
            int rank = 1;
            for (ScoredChunk chunk : vectorResults) {
                if (chunk == null || !seenVector.add(chunk.id())) {
                    continue;
                }
                MutableEntry entry = entries.computeIfAbsent(chunk.id(), ignored -> new MutableEntry(chunk));
                entry.rrfScore += 1.0 / (k + rank);
                entry.vectorScore = chunk.score();
                rank++;
            }
        }

        if (ftsResults != null) {
            Set<Long> seenFts = new HashSet<>();
            int rank = 1;
            for (ScoredChunk chunk : ftsResults) {
                if (chunk == null || !seenFts.add(chunk.id())) {
                    continue;
                }
                MutableEntry entry = entries.computeIfAbsent(chunk.id(), ignored -> new MutableEntry(chunk));
                entry.rrfScore += 1.0 / (k + rank);
                entry.ftsScore = chunk.score();
                rank++;
            }
        }

        return entries.values().stream()
                .map(MutableEntry::toSearchResult)
                .sorted(Comparator
                        .comparingDouble(SearchResult::score).reversed()
                        .thenComparing(Comparator.comparingDouble(SearchResult::vectorScore).reversed())
                        .thenComparing(Comparator.comparingDouble(SearchResult::ftsScore).reversed())
                        .thenComparingLong(SearchResult::chunkId))
                .limit(topK)
                .toList();
    }

    private static final class MutableEntry {
        private final ScoredChunk chunk;
        private double rrfScore;
        private double vectorScore;
        private double ftsScore;

        private MutableEntry(ScoredChunk chunk) {
            this.chunk = chunk;
        }

        private SearchResult toSearchResult() {
            return new SearchResult(
                    chunk.id(),
                    chunk.source(),
                    chunk.title(),
                    chunk.content(),
                    rrfScore,
                    vectorScore,
                    ftsScore
            );
        }
    }
}
