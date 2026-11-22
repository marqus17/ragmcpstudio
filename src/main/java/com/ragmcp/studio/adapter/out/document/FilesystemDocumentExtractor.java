package com.ragmcp.studio.adapter.out.document;

import com.ragmcp.studio.domain.model.DocumentContent;
import com.ragmcp.studio.domain.port.DocumentExtractorPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class FilesystemDocumentExtractor implements DocumentExtractorPort {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".md", ".txt", ".pdf", ".java", ".kt", ".py", ".ts", ".json", ".yml"
    );

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build"
    );

    public FilesystemDocumentExtractor() {
    }

    @Override
    public List<Path> listSupportedFiles(Path rootDir) {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        Path normalizedRoot = rootDir.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException("Root directory does not exist or is not a directory: " + normalizedRoot);
        }

        List<Path> discovered = new ArrayList<>();
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(normalizedRoot)) {
                        Path dirName = dir.getFileName();
                        if (dirName != null) {
                            String name = dirName.toString();
                            if (name.startsWith(".") || SKIPPED_DIRECTORIES.contains(name)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && isSupportedFile(file)) {
                        discovered.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan directory " + normalizedRoot + ": " + e.getMessage(), e);
        }

        discovered.sort(Comparator.comparing(path -> toNormalizedRelativePath(normalizedRoot, path)));
        return List.copyOf(discovered);
    }

    @Override
    public DocumentContent extract(Path rootDir, Path file) {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        Objects.requireNonNull(file, "file must not be null");

        Path normalizedRoot = rootDir.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!Files.exists(normalizedFile) || !Files.isRegularFile(normalizedFile)) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + normalizedFile);
        }

        String source = toNormalizedRelativePath(normalizedRoot, normalizedFile);
        String fileName = normalizedFile.getFileName().toString();
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        String content;
        try {
            if (lowerName.endsWith(".pdf")) {
                content = extractPdfText(normalizedFile);
            } else {
                content = Files.readString(normalizedFile, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract text from " + normalizedFile + ": " + e.getMessage(), e);
        }

        String title = deriveTitle(lowerName, fileName, content);
        return new DocumentContent(source, title, content);
    }

    private static boolean isSupportedFile(Path file) {
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0) {
            return false;
        }
        String extension = name.substring(dotIndex);
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    private static String extractPdfText(Path pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return text == null ? "" : text;
        }
    }

    private static String deriveTitle(String lowerFileName, String fileName, String content) {
        if (lowerFileName.endsWith(".md") && content != null && !content.isEmpty()) {
            String[] lines = content.split("\\R");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ")) {
                    String heading = trimmed.substring(2).trim();
                    if (heading.endsWith("#")) {
                        heading = heading.replaceAll("\\s+#+$", "").trim();
                    }
                    if (!heading.isEmpty()) {
                        return heading;
                    }
                }
            }
        }
        return fileName;
    }

    private static String toNormalizedRelativePath(Path normalizedRoot, Path normalizedFile) {
        try {
            return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return normalizedFile.getFileName().toString();
        }
    }
}
