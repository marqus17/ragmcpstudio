package com.ragmcp.studio.adapter.out.embedding;

import com.ragmcp.studio.domain.port.EmbeddingPort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.e5smallv2q.E5SmallV2QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;

public class EmbeddedOnnxEmbeddingAdapter implements EmbeddingPort {

    public static final String MODEL_NAME = "e5-small-v2-q";
    public static final int MODEL_DIMENSIONS = 384;

    private static final String DOCUMENT_PREFIX = "passage: ";
    private static final String QUERY_PREFIX = "query: ";

    private volatile EmbeddingModel model;

    public EmbeddedOnnxEmbeddingAdapter() {
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public int dimensions() {
        return MODEL_DIMENSIONS;
    }

    @Override
    public float[] embedDocument(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        return embedRaw(DOCUMENT_PREFIX + text);
    }

    @Override
    public float[] embedQuery(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        return embedRaw(QUERY_PREFIX + text);
    }

    private float[] embedRaw(String input) {
        Response<Embedding> response = getOrInitModel().embed(input);
        if (response == null || response.content() == null) {
            throw new IllegalStateException("Embedded ONNX model returned a null embedding");
        }
        float[] vector = response.content().vector();
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("Embedded ONNX model returned an empty embedding");
        }
        return vector;
    }

    private EmbeddingModel getOrInitModel() {
        EmbeddingModel current = this.model;
        if (current == null) {
            synchronized (this) {
                current = this.model;
                if (current == null) {
                    current = new E5SmallV2QuantizedEmbeddingModel();
                    this.model = current;
                }
            }
        }
        return current;
    }
}
