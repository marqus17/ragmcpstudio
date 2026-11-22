package com.ragmcp.studio.domain.port;

import com.ragmcp.studio.domain.model.DocumentContent;

import java.nio.file.Path;
import java.util.List;

public interface DocumentExtractorPort {

    List<Path> listSupportedFiles(Path rootDir);

    DocumentContent extract(Path rootDir, Path file);
}
