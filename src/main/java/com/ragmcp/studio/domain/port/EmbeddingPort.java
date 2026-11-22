package com.ragmcp.studio.domain.port;

public interface EmbeddingPort {

    String modelName();

    int dimensions();

    float[] embedDocument(String text);

    float[] embedQuery(String text);
}
