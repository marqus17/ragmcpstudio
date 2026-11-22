package com.ragmcp.studio.domain.model;

import java.util.Objects;

public record DocumentContent(
        String source,
        String title,
        String content
) {
    public DocumentContent {
        Objects.requireNonNull(source, "source must not be null");
        title = title == null ? "" : title;
        content = content == null ? "" : content;
    }
}
