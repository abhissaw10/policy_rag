package com.example.policyrag.store;

import com.example.policyrag.config.ClientConfiguration.ChromaProperties;
import com.example.policyrag.service.EmbeddingService;
import com.example.policyrag.api.ApiExceptionHandler.PolicyProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import java.util.List;

class ChromaVectorStoreTest {
    private static final String PATH = "http://chroma.test/api/v2/tenants/default_tenant/databases/default_database/collections";
    private static final String COLLECTION = """
            {"id":"collection-id","name":"hr_policies","metadata":{"embeddingModel":"%s","embeddingDimensions":384}}
            """.formatted(EmbeddingService.MODEL_ID);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ChromaVectorStore store = new ChromaVectorStore(builder,
            new ChromaProperties("http://chroma.test", "default_tenant", "default_database", "hr_policies"), event -> {});

    @Test
    void similaritySearchHasNoUploaderFilterAndReturnsAllMatchingPolicies() {
        server.expect(requestTo(PATH + "/hr_policies"))
                .andRespond(withSuccess(COLLECTION, MediaType.APPLICATION_JSON));
        server.expect(requestTo(PATH + "/collection-id/query"))
                .andExpect(jsonPath("$.where").doesNotExist())
                .andRespond(withSuccess("""
                        {"ids":[["document-0"]],"documents":[["Annual leave is 20 days."]],
                        "metadatas":[[{"employeeId":"E1234","filename":"leave.pdf","chunkIndex":0}]],"distances":[[0.2]]}
                        """, MediaType.APPLICATION_JSON));

        var hits = store.search(vector(), 8);
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().employeeId()).isEqualTo("E1234");
        server.verify();
    }

    @Test
    void reconstructsTheMatchedDocumentWithoutRestrictingTheRequesterToItsUploader() {
        server.expect(requestTo(PATH + "/hr_policies"))
                .andRespond(withSuccess(COLLECTION, MediaType.APPLICATION_JSON));
        server.expect(requestTo(PATH + "/collection-id/get"))
                .andExpect(jsonPath("$.where.filename").value("leave.pdf"))
                .andExpect(jsonPath("$.where.employeeId").doesNotExist())
                .andRespond(withSuccess("""
                        {"ids":["document-0","other-document-0"],"documents":["Annual leave is 20 days.","Unrelated upload."],
                        "metadatas":[{"employeeId":"E1234","filename":"leave.pdf","chunkIndex":0},
                        {"employeeId":"E9876","filename":"leave.pdf","chunkIndex":0}]}
                        """, MediaType.APPLICATION_JSON));

        var chunks = store.documentChunks("document", "leave.pdf");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().id()).isEqualTo("document-0");
        server.verify();
    }

    @Test
    void createsACosineCollectionAndLabelsItsEmbeddingSpaceAndRecords() {
        server.expect(requestTo(PATH + "/hr_policies")).andRespond(withResourceNotFound());
        server.expect(requestTo(PATH))
                .andExpect(jsonPath("$.configuration.hnsw.space").value("cosine"))
                .andExpect(jsonPath("$.metadata.embeddingModel").value(EmbeddingService.MODEL_ID))
                .andExpect(jsonPath("$.metadata.embeddingDimensions").value(384))
                .andRespond(withSuccess(COLLECTION, MediaType.APPLICATION_JSON));
        server.expect(requestTo(PATH + "/collection-id/add"))
                .andExpect(jsonPath("$.embeddings[0].length()").value(384))
                .andExpect(jsonPath("$.metadatas[0].embeddingModel").value(EmbeddingService.MODEL_ID))
                .andRespond(withSuccess());
        store.addAll(List.of(new PolicyChunk("document-0", "E1234", "leave.pdf", 0, "Policy text", vector())));
        server.verify();
    }

    @Test
    void rejectsALegacyCollectionEvenThoughItsVectorsHaveTheSameDimensions() {
        server.expect(requestTo(PATH + "/hr_policies"))
                .andRespond(withSuccess("{\"id\":\"legacy\",\"name\":\"hr_policies\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> store.search(vector(), 8)).isInstanceOf(PolicyProcessingException.class)
                .hasMessageContaining("incompatible embeddings");
        server.verify();
    }

    @Test
    void refusesToAppendSemanticVectorsToAnUnlabelledLegacyCollection() {
        server.expect(requestTo(PATH + "/hr_policies"))
                .andRespond(withSuccess("{\"id\":\"legacy\",\"name\":\"hr_policies\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> store.addAll(List.of(new PolicyChunk("document-0", "E1234", "leave.pdf", 0,
                "Policy text", vector())))).isInstanceOf(PolicyProcessingException.class)
                .hasMessageContaining("incompatible embeddings");
        server.verify();
    }

    private static float[] vector() {
        float[] vector = new float[EmbeddingService.DIMENSIONS];
        vector[0] = 1;
        return vector;
    }
}
