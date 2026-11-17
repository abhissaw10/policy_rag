package com.example.policyrag.store;

import java.util.List;

public interface VectorStore {
    void addAll(List<PolicyChunk> chunks);
    List<RetrievedChunk> search(float[] embedding, int limit);
    List<RetrievedChunk> documentChunks(String documentId, String filename);
}
