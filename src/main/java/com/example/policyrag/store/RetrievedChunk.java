package com.example.policyrag.store;

public record RetrievedChunk(String id, String employeeId, String filename, int chunkIndex,
                             String content, double similarity) {
    public String documentId() {
        int separator = id.lastIndexOf('-');
        return separator < 0 ? id : id.substring(0, separator);
    }
}
