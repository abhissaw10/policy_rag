package com.example.policyrag.store;

public record PolicyChunk(String id, String employeeId, String filename, int chunkIndex,
                          String content, float[] embedding) {
}
