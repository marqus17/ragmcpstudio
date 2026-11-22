package com.ragmcp.studio.adapter.out.document;

import com.ragmcp.studio.domain.model.DocumentContent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemDocumentExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void listSupportedFilesFindsSupportedExtensionsAndSkipsIgnoredDirectories() throws Exception {
        // Supported files across nested folders (including uppercase extension)
        writeText(tempDir.resolve("README.md"), "# Root");
        writeText(tempDir.resolve("notes.TXT"), "Plain text");
        writeText(tempDir.resolve("src/App.java"), "class App {}");
        writeText(tempDir.resolve("src/Main.kt"), "fun main() {}");
        writeText(tempDir.resolve("scripts/run.py"), "print('hi')");
        writeText(tempDir.resolve("web/index.ts"), "export const x = 1;");
        writeText(tempDir.resolve("config/settings.json"), "{}");
        writeText(tempDir.resolve("config/app.yml"), "key: value");
        createSimplePdf(tempDir.resolve("docs/manual.pdf"), "PDF content");

        // Unsupported files
        writeText(tempDir.resolve("image.png"), "not-an-image");
        writeText(tempDir.resolve("archive.zip"), "binary");

        // Ignored directories
        writeText(tempDir.resolve(".git/config.txt"), "ignored");
        writeText(tempDir.resolve(".hidden/secret.md"), "# Ignored");
        writeText(tempDir.resolve("node_modules/pkg/index.ts"), "ignored");
        writeText(tempDir.resolve("target/generated.java"), "ignored");
        writeText(tempDir.resolve("build/output.json"), "ignored");

        FilesystemDocumentExtractor extractor = new FilesystemDocumentExtractor();
        List<Path> files = extractor.listSupportedFiles(tempDir);

        List<String> relativePaths = files.stream()
                .map(p -> tempDir.relativize(p).toString().replace('\\', '/'))
                .toList();

        assertThat(relativePaths).containsExactly(
                "README.md",
                "config/app.yml",
                "config/settings.json",
                "docs/manual.pdf",
                "notes.TXT",
                "scripts/run.py",
                "src/App.java",
                "src/Main.kt",
                "web/index.ts"
        );
    }

    @Test
    void extractMarkdownUsesFirstH1HeadingOrFallsBackToFilename() throws Exception {
        Path withHeading = tempDir.resolve("docs/guide.md");
        writeText(withHeading, """
                Intro line
                ## Subheading
                # Architecture Overview
                Body text here.
                """);

        Path withoutHeading = tempDir.resolve("docs/plain.md");
        writeText(withoutHeading, "## Only level two\nSome details.");

        FilesystemDocumentExtractor extractor = new FilesystemDocumentExtractor();

        DocumentContent doc1 = extractor.extract(tempDir, withHeading);
        assertThat(doc1.source()).isEqualTo("docs/guide.md");
        assertThat(doc1.title()).isEqualTo("Architecture Overview");
        assertThat(doc1.content()).contains("Body text here.");

        DocumentContent doc2 = extractor.extract(tempDir, withoutHeading);
        assertThat(doc2.source()).isEqualTo("docs/plain.md");
        assertThat(doc2.title()).isEqualTo("plain.md");
    }

    @Test
    void extractJavaAndPdfFiles() throws Exception {
        Path javaFile = tempDir.resolve("src/Example.java");
        writeText(javaFile, "public class Example { int x = 42; }");

        Path pdfFile = tempDir.resolve("docs/spec.pdf");
        createSimplePdf(pdfFile, "Hello PDFBox 3 Vector Store");

        FilesystemDocumentExtractor extractor = new FilesystemDocumentExtractor();

        DocumentContent javaDoc = extractor.extract(tempDir, javaFile);
        assertThat(javaDoc.source()).isEqualTo("src/Example.java");
        assertThat(javaDoc.title()).isEqualTo("Example.java");
        assertThat(javaDoc.content()).contains("int x = 42;");

        DocumentContent pdfDoc = extractor.extract(tempDir, pdfFile);
        assertThat(pdfDoc.source()).isEqualTo("docs/spec.pdf");
        assertThat(pdfDoc.title()).isEqualTo("spec.pdf");
        assertThat(pdfDoc.content()).contains("Hello PDFBox 3 Vector Store");
    }

    private static void writeText(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void createSimplePdf(Path pdfPath, String text) throws Exception {
        Files.createDirectories(pdfPath.getParent());
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(pdfPath.toFile());
        }
    }
}
