package com.ragmcp.studio.adapter.in.web.dto;

import com.ragmcp.studio.domain.model.DatabaseMetadata;
import com.ragmcp.studio.domain.model.SourceSummary;

import java.util.List;

public record DbInspectResponse(
        String path,
        DatabaseMetadata metadata,
        List<SourceSummary> sources
) {
}
