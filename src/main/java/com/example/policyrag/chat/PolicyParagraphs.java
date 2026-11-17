package com.example.policyrag.chat;

import com.example.policyrag.api.ApiExceptionHandler.PolicyProcessingException;
import com.example.policyrag.service.EmbeddingService;
import com.example.policyrag.store.RetrievedChunk;
import com.example.policyrag.store.VectorStore;
import com.example.policyrag.observability.ChatTrace;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class PolicyParagraphs {
    private final VectorStore store;
    private final EmbeddingService embeddings;
    private final PromptGuard guard;
    public PolicyParagraphs(VectorStore store, EmbeddingService embeddings, PromptGuard guard) {
        this.store = store;
        this.embeddings = embeddings;
        this.guard = guard;
    }

    public List<Paragraph> candidates(List<RetrievedChunk> hits, float[] query, ChatTrace trace) {
        List<Paragraph> candidates = new ArrayList<>();
        Set<String> seenDocuments = new HashSet<>();
        Set<String> seenParagraphs = new HashSet<>();
        for (RetrievedChunk hit : hits) {
            if (hit.similarity() < .05 || !seenDocuments.add(hit.documentId())) continue;
            List<RetrievedChunk> chunks = trace.tool("chroma.document.get",
                    () -> store.documentChunks(hit.documentId(), hit.filename()));
            if (chunks.isEmpty()) continue;
            StringBuilder document = new StringBuilder();
            for (int index = 0; index < chunks.size(); index++) {
                RetrievedChunk chunk = chunks.get(index);
                if (chunk.chunkIndex() != index || !hit.employeeId().equals(chunk.employeeId())
                        || !hit.documentId().equals(chunk.documentId()) || !hit.filename().equals(chunk.filename())) {
                    throw new PolicyProcessingException("The policy index is incomplete. Please upload this policy again.");
                }
                document.append(chunk.content());
            }
            for (Section section : sections(document.toString())) {
                String heading = section.heading();
                String paragraph = section.body();
                if (paragraph.isBlank() || guard.unsafe(paragraph)) continue;
                if (!seenParagraphs.add(hit.filename() + "\n" + heading + "\n" + paragraph)) continue;
                double similarity = dot(query, embeddings.embedDocument(heading + "\n" + paragraph));
                if (similarity >= .05) candidates.add(new Paragraph("", hit.filename(), paragraph, similarity, heading));
            }
        }
        List<Paragraph> selected = candidates.stream()
                .sorted(Comparator.comparingDouble(Paragraph::similarity).reversed()).limit(16).toList();
        List<Paragraph> numbered = new ArrayList<>();
        int characters = 0;
        for (Paragraph paragraph : selected) {
            // Do not cut a paragraph to fit the LLM context. Omit only oversized evidence, never fragments.
            if (characters + paragraph.text().length() > 24000) continue;
            characters += paragraph.text().length();
            numbered.add(new Paragraph("p" + numbered.size(), paragraph.filename(), paragraph.text(), paragraph.similarity(), paragraph.heading()));
        }
        return numbered;
    }

    private double dot(float[] left, float[] right) {
        double score = 0;
        for (int index = 0; index < left.length; index++) score += left[index] * right[index];
        return score;
    }

    private List<Section> sections(String document) {
        List<Section> result = new ArrayList<>();
        String heading = "";
        StringBuilder body = new StringBuilder();
        for (String part : document.split("\\R\\s*\\R")) {
            String block = part.strip();
            if (block.isBlank()) continue;
            if (isHeading(block)) {
                if (!body.isEmpty()) result.add(new Section(heading, body.toString()));
                body.setLength(0);
                heading = block;
            } else if (heading.isEmpty()) {
                result.add(new Section("", block));
            } else {
                if (!body.isEmpty()) body.append("\n\n");
                body.append(block);
            }
        }
        if (!body.isEmpty()) result.add(new Section(heading, body.toString()));
        return result;
    }
    private record Section(String heading, String body) { }

    public static boolean isHeading(String text) {
        String value = text.strip().replaceAll("\\s+", " ");
        if (value.isEmpty() || value.split(" ").length > 14) return false;
        String title = value.replaceFirst("^(?:\\d+(?:\\.\\d+)+[.)]?|\\d+[.)])\\s+", "");
        if (title.matches(".*[.!?;]$") || title.matches(".*\\d.*")
                || title.toLowerCase(java.util.Locale.ROOT).matches(
                ".*\\b(is|are|must|shall|can|cannot|may|will|receive|requires?|provides?|entitled|eligible|without|maximum|minimum|days?|months?|weeks?|years?|hours?|calendar|consecutive|paid|unpaid)\\b.*")) return false;
        if (!title.equals(value) || title.endsWith(":")) return true;
        return title.matches("[\\p{Lu}][\\p{L}&/-]*(?: (?:[\\p{Lu}][\\p{L}&/-]*|and|of|the|for|to)){0,10}");
    }

    public record Paragraph(String id, String filename, String text, double similarity, String heading) {
        public Paragraph(String id, String filename, String text, double similarity) { this(id, filename, text, similarity, ""); }
    }
}
