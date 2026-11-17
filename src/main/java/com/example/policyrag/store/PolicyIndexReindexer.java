package com.example.policyrag.store;

import com.example.policyrag.config.ClientConfiguration.ChromaProperties;
import com.example.policyrag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Opt-in reindexing: copies original text and attribution, never legacy vectors or Groq-generated text. */
@Component
@ConditionalOnProperty(name = "app.chroma.reindex-from")
public class PolicyIndexReindexer implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyIndexReindexer.class);
    private final RestClient client;
    private final ChromaProperties properties;
    private final EmbeddingService embeddings;
    private final ChromaVectorStore destination;
    private final String sourceCollection;

    public PolicyIndexReindexer(RestClient.Builder builder, ChromaProperties properties, EmbeddingService embeddings,
                                ChromaVectorStore destination, @Value("${app.chroma.reindex-from}") String sourceCollection) {
        this.client = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.embeddings = embeddings;
        this.destination = destination;
        this.sourceCollection = sourceCollection;
    }

    @Override
    public void run(ApplicationArguments args) {
        reindex();
    }

    public int reindex() {
        if (sourceCollection == null || !sourceCollection.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,510}[A-Za-z0-9]")
                || sourceCollection.equals(properties.collection())) {
            throw new IllegalArgumentException("Reindexing requires a different source and destination Chroma collection");
        }
        String path = "/api/v2/tenants/" + properties.tenant() + "/databases/" + properties.database() + "/collections";
        CollectionSource source = client.get().uri(path + "/{collection}", sourceCollection)
                .retrieve().body(CollectionSource.class);
        if (source == null || source.id() == null) throw new IllegalStateException("Reindex source collection is unavailable");
        int copied = 0;
        for (int offset = 0; ; offset += 128) {
            SourceRecords records = client.post().uri(path + "/" + source.id() + "/get")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("offset", offset, "limit", 128, "include", List.of("documents", "metadatas")))
                    .retrieve().body(SourceRecords.class);
            if (records == null || records.ids() == null || records.documents() == null || records.metadatas() == null
                    || records.ids().size() != records.documents().size()
                    || records.ids().size() != records.metadatas().size()) {
                throw new IllegalStateException("Invalid policy records returned during reindexing");
            }
            List<PolicyChunk> chunks = new ArrayList<>();
            for (int index = 0; index < records.ids().size(); index++) {
                Map<String, Object> metadata = records.metadatas().get(index);
                String text = records.documents().get(index);
                if (metadata == null || !(metadata.get("employeeId") instanceof String employeeId)
                        || !(metadata.get("filename") instanceof String filename)
                        || !(metadata.get("chunkIndex") instanceof Number chunkIndex)) {
                    throw new IllegalStateException("Policy attribution is missing from the source index");
                }
                chunks.add(new PolicyChunk(records.ids().get(index), employeeId, filename,
                        chunkIndex.intValue(), text, embeddings.embedDocument(text)));
            }
            destination.upsertAll(chunks);
            copied += chunks.size();
            if (chunks.size() < 128) break;
        }
        LOGGER.info("Reindexed {} policy chunks from {} into {} using {}", copied, sourceCollection,
                properties.collection(), EmbeddingService.MODEL_ID);
        return copied;
    }

    record CollectionSource(String id) { }
    record SourceRecords(List<String> ids, List<String> documents, List<Map<String, Object>> metadatas) { }
}
