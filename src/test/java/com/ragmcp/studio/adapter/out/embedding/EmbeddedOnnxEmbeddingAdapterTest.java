package com.ragmcp.studio.adapter.out.embedding;

import com.ragmcp.studio.domain.service.VectorMath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddedOnnxEmbeddingAdapterTest {

    private final EmbeddedOnnxEmbeddingAdapter adapter = new EmbeddedOnnxEmbeddingAdapter();

    @Test
    void embedsDocumentsAndQueriesOfflineWithExpectedDimensionsAndSemantics() {
        assertThat(adapter.modelName()).isEqualTo("e5-small-v2-q");
        assertThat(adapter.dimensions()).isEqualTo(384);

        float[] docJava = adapter.embedDocument("Java 21 virtual threads improve concurrency in Spring Boot applications.");
        float[] docCooking = adapter.embedDocument("Bake the chocolate cake at 180 degrees Celsius for 35 minutes.");
        float[] query = adapter.embedQuery("How do virtual threads help Java concurrency?");

        assertThat(docJava).hasSize(384);
        assertThat(docCooking).hasSize(384);
        assertThat(query).hasSize(384);

        double simRelevant = VectorMath.cosineSimilarity(query, docJava);
        double simIrrelevant = VectorMath.cosineSimilarity(query, docCooking);
        assertThat(simRelevant).isGreaterThan(simIrrelevant);
    }

    @Test
    void initializesEvenWhenThreadContextClassLoaderCannotReadAppResources() {
        EmbeddedOnnxEmbeddingAdapter freshAdapter = new EmbeddedOnnxEmbeddingAdapter();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
            float[] vector = freshAdapter.embedQuery("spring boot fat jar classloader test");
            assertThat(vector).hasSize(384);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> adapter.embedDocument(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.embedQuery(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
