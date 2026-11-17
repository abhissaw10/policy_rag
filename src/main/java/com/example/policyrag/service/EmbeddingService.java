package com.example.policyrag.service;

import org.springframework.stereotype.Component;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;

@Component
public class EmbeddingService {

    public static final int DIMENSIONS = 384;
    // Version the entire embedding contract, including query instructions and normalization.
    public static final String MODEL_ID = "bge-small-en-v1.5-q-lc4j-1.23.0-beta33-v1";
    private static final String QUERY_PREFIX = "Represent this sentence for searching relevant passages: ";

    /** Learned semantic embeddings, computed locally with model weights bundled in the Maven dependency. */
    public float[] embedDocument(String text) {
        return encode(requireText(text));
    }

    public float[] embedQuery(String question) {
        return encode(QUERY_PREFIX + requireText(question));
    }

    private String requireText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text must not be blank");
        return text.strip();
    }

    private float[] encode(String text) {
        float[] vector = ModelHolder.MODEL.embed(text).content().vector().clone();
        if (vector.length != DIMENSIONS) throw new IllegalStateException("Unexpected semantic embedding dimensions");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Invalid semantic embedding");
            norm += value * value;
        }
        if (norm == 0) throw new IllegalStateException("Empty semantic embedding");
        float divisor = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) vector[i] /= divisor;
        return vector;
    }

    private static class ModelHolder {
        private static final BgeSmallEnV15QuantizedEmbeddingModel MODEL = new BgeSmallEnV15QuantizedEmbeddingModel();
    }
}
