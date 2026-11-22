package com.ragmcp.studio.domain.service;

import com.ragmcp.studio.domain.model.Chunk;
import com.ragmcp.studio.domain.model.DocumentContent;
import com.ragmcp.studio.domain.model.IngestionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DocumentChunker {

    public static final int DEFAULT_CHUNK_SIZE = IngestionConfig.DEFAULT_CHUNK_SIZE;
    public static final int DEFAULT_CHUNK_OVERLAP = IngestionConfig.DEFAULT_CHUNK_OVERLAP;

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?\\s*$");
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

    public List<Chunk> chunk(DocumentContent document) {
        return chunk(document, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    public List<Chunk> chunk(DocumentContent document, int chunkSize, int chunkOverlap) {
        Objects.requireNonNull(document, "document must not be null");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
        }

        String normalized = normalizeLineEndings(document.content()).trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<Section> sections = extractMarkdownSections(document.title(), normalized);
        List<Chunk> result = new ArrayList<>();

        for (Section section : sections) {
            String sectionText = section.text().trim();
            if (sectionText.isEmpty()) {
                continue;
            }
            if (sectionText.length() <= chunkSize) {
                result.add(new Chunk(null, document.source(), section.title(), sectionText, null));
            } else {
                List<String> pieces = splitText(sectionText, chunkSize, chunkOverlap);
                for (String piece : pieces) {
                    String trimmed = piece.trim();
                    if (!trimmed.isEmpty()) {
                        result.add(new Chunk(null, document.source(), section.title(), trimmed, null));
                    }
                }
            }
        }

        return List.copyOf(result);
    }

    private List<Section> extractMarkdownSections(String baseTitle, String text) {
        String[] lines = text.split("\n", -1);
        List<Section> sections = new ArrayList<>();
        StringBuilder currentBody = new StringBuilder();
        String currentHeading = null;
        boolean inCodeFence = false;
        boolean foundAnyHeading = false;

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~")) {
                inCodeFence = !inCodeFence;
                appendLine(currentBody, line);
                continue;
            }

            if (!inCodeFence) {
                Matcher matcher = ATX_HEADING.matcher(line);
                if (matcher.matches()) {
                    foundAnyHeading = true;
                    if (!currentBody.isEmpty() && !currentBody.toString().isBlank()) {
                        sections.add(new Section(
                                combineTitle(baseTitle, currentHeading),
                                currentBody.toString().trim()
                        ));
                    }
                    currentBody.setLength(0);
                    currentHeading = matcher.group(2).trim();
                    appendLine(currentBody, line);
                    continue;
                }
            }

            appendLine(currentBody, line);
        }

        if (!foundAnyHeading) {
            return List.of(new Section(baseTitle, text));
        }

        if (!currentBody.isEmpty() && !currentBody.toString().isBlank()) {
            sections.add(new Section(
                    combineTitle(baseTitle, currentHeading),
                    currentBody.toString().trim()
            ));
        }

        return sections;
    }

    private String combineTitle(String baseTitle, String heading) {
        if (heading == null || heading.isBlank()) {
            return baseTitle == null ? "" : baseTitle;
        }
        if (baseTitle == null || baseTitle.isBlank() || baseTitle.equalsIgnoreCase(heading)) {
            return heading;
        }
        return baseTitle + " > " + heading;
    }

    private List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        List<String> units = splitIntoUnits(text, chunkSize, chunkOverlap);
        List<String> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String unit : units) {
            if (unit.isEmpty()) {
                continue;
            }
            if (buffer.isEmpty()) {
                buffer.append(unit);
                continue;
            }

            String separator = "\n\n";
            if (buffer.length() + separator.length() + unit.length() <= chunkSize) {
                buffer.append(separator).append(unit);
            } else {
                String flushed = buffer.toString().trim();
                if (!flushed.isEmpty()) {
                    chunks.add(flushed);
                }
                buffer.setLength(0);

                String overlap = extractOverlapTail(flushed, chunkOverlap);
                if (!overlap.isEmpty() && overlap.length() + 1 + unit.length() <= chunkSize) {
                    buffer.append(overlap).append('\n').append(unit);
                } else {
                    buffer.append(unit);
                }
            }
        }

        if (!buffer.isEmpty()) {
            String remaining = buffer.toString().trim();
            if (!remaining.isEmpty()) {
                chunks.add(remaining);
            }
        }

        return chunks;
    }

    private List<String> splitIntoUnits(String text, int chunkSize, int chunkOverlap) {
        List<String> units = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_SPLIT.split(text);

        for (String rawParagraph : paragraphs) {
            String paragraph = rawParagraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() <= chunkSize) {
                units.add(paragraph);
            } else {
                splitLargeParagraph(paragraph, chunkSize, chunkOverlap, units);
            }
        }

        return units;
    }

    private void splitLargeParagraph(String paragraph, int chunkSize, int chunkOverlap, List<String> out) {
        String[] sentences = SENTENCE_SPLIT.split(paragraph);
        if (sentences.length == 1) {
            splitByCharacterWindow(paragraph, chunkSize, chunkOverlap, out);
            return;
        }

        StringBuilder current = new StringBuilder();
        for (String rawSentence : sentences) {
            String sentence = rawSentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    out.add(current.toString().trim());
                    current.setLength(0);
                }
                splitByCharacterWindow(sentence, chunkSize, chunkOverlap, out);
                continue;
            }

            if (current.isEmpty()) {
                current.append(sentence);
            } else if (current.length() + 1 + sentence.length() <= chunkSize) {
                current.append(' ').append(sentence);
            } else {
                String flushed = current.toString().trim();
                out.add(flushed);
                current.setLength(0);

                String overlap = extractOverlapTail(flushed, chunkOverlap);
                if (!overlap.isEmpty() && overlap.length() + 1 + sentence.length() <= chunkSize) {
                    current.append(overlap).append(' ').append(sentence);
                } else {
                    current.append(sentence);
                }
            }
        }

        if (!current.isEmpty()) {
            out.add(current.toString().trim());
        }
    }

    private void splitByCharacterWindow(String text, int chunkSize, int chunkOverlap, List<String> out) {
        int len = text.length();
        int start = 0;

        while (start < len) {
            int maxEnd = Math.min(len, start + chunkSize);
            int end = maxEnd;

            if (maxEnd < len) {
                int minBreak = start + (chunkSize / 2);
                int lastSpace = findLastWhitespace(text, minBreak, maxEnd);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }

            String slice = text.substring(start, end).trim();
            if (!slice.isEmpty()) {
                out.add(slice);
            }

            if (end >= len) {
                break;
            }

            int nextStart = end - chunkOverlap;
            if (nextStart <= start) {
                nextStart = Math.max(start + 1, end);
            } else {
                while (nextStart < end && Character.isWhitespace(text.charAt(nextStart))) {
                    nextStart++;
                }
            }
            start = nextStart;
        }
    }

    private int findLastWhitespace(String text, int minIndex, int maxIndex) {
        for (int i = maxIndex - 1; i >= minIndex; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private String extractOverlapTail(String text, int chunkOverlap) {
        if (chunkOverlap <= 0 || text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= chunkOverlap) {
            return text;
        }
        int start = text.length() - chunkOverlap;
        int boundary = start;
        while (boundary < text.length() && !Character.isWhitespace(text.charAt(boundary))) {
            boundary++;
        }
        if (boundary < text.length() && boundary - start < chunkOverlap / 2) {
            return text.substring(boundary).trim();
        }
        return text.substring(start).trim();
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(line);
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record Section(String title, String text) {
    }
}
