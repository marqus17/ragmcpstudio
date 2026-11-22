package com.ragmcp.studio.domain.model;

import java.util.Arrays;
import java.util.Objects;

public record Chunk(
        Long id,
        String source,
        String title,
        String content,
        float[] embedding
) {
    public Chunk {
        Objects.requireNonNull(source, "source must not be null");
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        embedding = embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
    }

    @Override
    public float[] embedding() {
        return embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
    }

    public int dimensions() {
        return embedding == null ? 0 : embedding.length;
    }

    public Chunk withId(Long newId) {
        return new Chunk(newId, source, title, content, embedding);
    }

    public Chunk withEmbedding(float[] newEmbedding) {
        return new Chunk(id, source, title, content, newEmbedding);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Chunk other)) {
            return false;
        }
        return Objects.equals(id, other.id)
                && Objects.equals(source, other.source)
                && Objects.equals(title, other.title)
                && Objects.equals(content, other.content)
                && Arrays.equals(embedding, other.embedding);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, source, title, content);
        result = 31 * result + Arrays.hashCode(embedding);
        return result;
    }

    @Override
    public String toString() {
        return "Chunk[id=" + id
                + ", source=" + source
                + ", title=" + title
                + ", contentLength=" + content.length()
                + ", embeddingDims=" + (embedding == null ? 0 : embedding.length)
                + "]";
    }
}
