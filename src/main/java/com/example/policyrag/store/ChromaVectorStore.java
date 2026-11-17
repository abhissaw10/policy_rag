package com.example.policyrag.store;

import com.example.policyrag.api.ApiExceptionHandler.PolicyProcessingException;
import com.example.policyrag.config.ClientConfiguration.ChromaProperties;
import com.example.policyrag.service.EmbeddingService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class ChromaVectorStore implements VectorStore {

    private final RestClient client;
    private final ChromaProperties properties;
    private final ApplicationEventPublisher events;

    public ChromaVectorStore(RestClient.Builder builder, ChromaProperties properties, ApplicationEventPublisher events) {
        this.client = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.events = events;
    }

    @Override
    public void addAll(List<PolicyChunk> chunks) {
        write(chunks, "add");
    }

    /** Idempotent copying of existing policy text into this model's collection. */
    public void upsertAll(List<PolicyChunk> chunks) {
        write(chunks, "upsert");
    }

    private void write(List<PolicyChunk> chunks, String operation) {
        if (chunks.isEmpty()) {
            return;
        }
        for (PolicyChunk chunk : chunks) validateDimensions(chunk.embedding());
        try {
            CollectionResponse collection = getOrCreateCollection();
            AddRecordsRequest request = new AddRecordsRequest(
                    chunks.stream().map(PolicyChunk::id).toList(),
                    chunks.stream().map(PolicyChunk::embedding).map(this::boxed).toList(),
                    chunks.stream().map(PolicyChunk::content).toList(),
                    chunks.stream().map(chunk -> Map.<String, Object>of(
                            "employeeId", chunk.employeeId(),
                            "filename", chunk.filename(),
                            "chunkIndex", chunk.chunkIndex(), "embeddingModel", EmbeddingService.MODEL_ID)).toList()
            );
            client.post().uri(collectionPath() + "/" + collection.id() + "/" + operation)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            chunks.stream().map(PolicyChunk::employeeId).distinct()
                    .forEach(id -> events.publishEvent(new PoliciesUpdatedEvent(id)));
        } catch (RestClientException exception) {
            throw new PolicyProcessingException("The Chroma vector store is unavailable", exception);
        }
    }

    @Override
    public List<RetrievedChunk> search(float[] embedding, int limit) {
        validateDimensions(embedding);
        try {
            CollectionResponse collection = findCollection();
            QueryResponse result = client.post().uri(collectionPath() + "/" + collection.id() + "/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query_embeddings", List.of(boxed(embedding)), "n_results", limit,
                            "include", List.of("documents", "metadatas", "distances")))
                    .retrieve().body(QueryResponse.class);
            if (result == null || result.ids() == null || result.ids().isEmpty()) return List.of();
            List<RetrievedChunk> chunks = new ArrayList<>();
            for (int index = 0; index < result.ids().getFirst().size(); index++) {
                chunks.add(record(result.ids().getFirst().get(index), result.documents().getFirst().get(index),
                        result.metadatas().getFirst().get(index), 1 - result.distances().getFirst().get(index)));
            }
            return chunks;
        } catch (HttpClientErrorException.NotFound missing) {
            return List.of();
        } catch (RestClientException exception) {
            throw new PolicyProcessingException("The policy search service is unavailable. Please try again.", exception);
        }
    }

    @Override
    public List<RetrievedChunk> documentChunks(String documentId, String filename) {
        try {
            CollectionResponse collection = findCollection();
            List<RetrievedChunk> chunks = new ArrayList<>();
            for (int offset = 0; offset < 4096; offset += 256) {
                GetResponse result = client.post().uri(collectionPath() + "/" + collection.id() + "/get")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("where", Map.of("filename", filename),
                                "offset", offset, "limit", 256, "include", List.of("documents", "metadatas")))
                        .retrieve().body(GetResponse.class);
                if (result == null || result.ids() == null) break;
                for (int index = 0; index < result.ids().size(); index++) {
                    RetrievedChunk chunk = record(result.ids().get(index), result.documents().get(index),
                            result.metadatas().get(index), 0);
                    if (documentId.equals(chunk.documentId()) && filename.equals(chunk.filename())) chunks.add(chunk);
                }
                if (result.ids().size() < 256) return chunks.stream()
                        .sorted(Comparator.comparingInt(RetrievedChunk::chunkIndex)).toList();
            }
            // Never present an incomplete document as a complete source paragraph.
            throw new PolicyProcessingException("This policy is too large to reconstruct safely for chat.");
        } catch (HttpClientErrorException.NotFound missing) {
            return List.of();
        } catch (RestClientException exception) {
            throw new PolicyProcessingException("The policy search service is unavailable. Please try again.", exception);
        }
    }

    private RetrievedChunk record(String id, String text, Map<String, Object> metadata, double similarity) {
        return new RetrievedChunk(id, (String) metadata.get("employeeId"), (String) metadata.get("filename"),
                ((Number) metadata.get("chunkIndex")).intValue(), text, similarity);
    }

    private CollectionResponse findCollection() {
        CollectionResponse collection = client.get().uri(collectionPath() + "/" + properties.collection())
                .retrieve().body(CollectionResponse.class);
        if (collection == null) throw new PolicyProcessingException("The policy collection is unavailable.");
        return compatible(collection);
    }

    private CollectionResponse getOrCreateCollection() {
        try {
            return findCollection();
        } catch (HttpClientErrorException.NotFound notFound) {
            CollectionResponse created = client.post().uri(collectionPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "name", properties.collection(),
                            "get_or_create", true,
                            "metadata", Map.of("embeddingModel", EmbeddingService.MODEL_ID,
                                    "embeddingDimensions", EmbeddingService.DIMENSIONS),
                            "configuration", Map.of("hnsw", Map.of("space", "cosine"))))
                    .retrieve().body(CollectionResponse.class);
            if (created == null) {
                throw new PolicyProcessingException("Chroma did not create the policy collection");
            }
            return compatible(created);
        }
    }

    private CollectionResponse compatible(CollectionResponse collection) {
        if (collection.metadata() == null
                || !EmbeddingService.MODEL_ID.equals(collection.metadata().get("embeddingModel"))
                || !(collection.metadata().get("embeddingDimensions") instanceof Number dimensions)
                || dimensions.intValue() != EmbeddingService.DIMENSIONS) {
            throw new PolicyProcessingException("This policy collection uses incompatible embeddings. Select a new collection and reindex or upload the policies again.");
        }
        return collection;
    }

    private void validateDimensions(float[] vector) {
        if (vector == null || vector.length != EmbeddingService.DIMENSIONS) {
            throw new PolicyProcessingException("The policy vector does not match the configured semantic embedding model.");
        }
    }

    private String collectionPath() {
        return "/api/v2/tenants/" + properties.tenant()
                + "/databases/" + properties.database() + "/collections";
    }

    private List<Float> boxed(float[] vector) {
        return Arrays.stream(toDoubleArray(vector)).mapToObj(value -> (float) value).toList();
    }

    private double[] toDoubleArray(float[] vector) {
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i];
        }
        return result;
    }

    record CollectionResponse(String id, String name, Map<String, Object> metadata) {
    }

    record QueryResponse(List<List<String>> ids, List<List<String>> documents,
                         List<List<Map<String, Object>>> metadatas, List<List<Double>> distances) {
    }

    record GetResponse(List<String> ids, List<String> documents, List<Map<String, Object>> metadatas) {
    }

    record AddRecordsRequest(List<String> ids, List<List<Float>> embeddings,
                             List<String> documents, List<Map<String, Object>> metadatas) {
    }
}
