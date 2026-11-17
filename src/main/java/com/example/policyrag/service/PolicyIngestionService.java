package com.example.policyrag.service;

import com.example.policyrag.store.PolicyChunk;
import com.example.policyrag.store.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PolicyIngestionService {

    private final UploadValidator validator;
    private final PdfTextExtractor textExtractor;
    private final SemanticChunker semanticChunker;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public PolicyIngestionService(UploadValidator validator, PdfTextExtractor textExtractor,
                                  SemanticChunker semanticChunker, EmbeddingService embeddingService,
                                  VectorStore vectorStore) {
        this.validator = validator;
        this.textExtractor = textExtractor;
        this.semanticChunker = semanticChunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    public int ingest(String employeeId, String checksum, MultipartFile file) {
        byte[] bytes = validator.validate(employeeId, checksum, file);
        String text = textExtractor.extract(bytes);
        List<String> semanticChunks = semanticChunker.chunk(text);
        String documentId = UUID.randomUUID().toString();
        List<PolicyChunk> chunks = new ArrayList<>(semanticChunks.size());
        for (int i = 0; i < semanticChunks.size(); i++) {
            String content = semanticChunks.get(i);
            chunks.add(new PolicyChunk(documentId + "-" + i, employeeId, file.getOriginalFilename(), i,
                    content, embeddingService.embedDocument(content)));
        }
        vectorStore.addAll(chunks);
        return chunks.size();
    }
}
