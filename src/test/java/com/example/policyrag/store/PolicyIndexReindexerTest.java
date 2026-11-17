package com.example.policyrag.store;

import com.example.policyrag.config.ClientConfiguration.ChromaProperties;
import com.example.policyrag.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PolicyIndexReindexerTest {
    private static final String PATH = "http://chroma.test/api/v2/tenants/default_tenant/databases/default_database/collections";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final ChromaVectorStore destination = mock(ChromaVectorStore.class);
    private final ChromaProperties properties = new ChromaProperties("http://chroma.test", "default_tenant", "default_database", "semantic_policies");

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reembedsStoredTextAndPreservesDocumentIdsAndAttributionWithoutReadingOldVectors() {
        server.expect(requestTo(PATH + "/hr_policies"))
                .andRespond(withSuccess("{\"id\":\"old-collection\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(PATH + "/old-collection/get"))
                .andRespond(withSuccess("""
                        {"ids":["original-0","original-1"],"documents":["Meal eligibility.","Meal approval."],
                        "metadatas":[{"employeeId":"E1234","filename":"meals.pdf","chunkIndex":0},
                        {"employeeId":"E1234","filename":"meals.pdf","chunkIndex":1}]}
                        """, MediaType.APPLICATION_JSON));
        float[] first = new float[384]; first[0] = 1;
        float[] second = new float[384]; second[1] = 1;
        when(embeddings.embedDocument("Meal eligibility.")).thenReturn(first);
        when(embeddings.embedDocument("Meal approval.")).thenReturn(second);
        var reindexer = new PolicyIndexReindexer(builder, properties, embeddings, destination, "hr_policies");
        assertThat(reindexer.reindex()).isEqualTo(2);
        ArgumentCaptor<List<PolicyChunk>> captured = ArgumentCaptor.forClass((Class) List.class);
        verify(destination).upsertAll(captured.capture());
        assertThat(captured.getValue()).extracting(PolicyChunk::id).containsExactly("original-0", "original-1");
        assertThat(captured.getValue()).allSatisfy(chunk -> {
            assertThat(chunk.employeeId()).isEqualTo("E1234");
            assertThat(chunk.filename()).isEqualTo("meals.pdf");
        });
        assertThat(captured.getValue().getFirst().embedding()).containsExactly(first);
        assertThat(captured.getValue().getLast().embedding()).containsExactly(second);
        server.verify();
    }

    @Test
    void refusesToOverwriteTheSourceCollection() {
        var reindexer = new PolicyIndexReindexer(builder, properties, embeddings, destination, "semantic_policies");
        assertThatIllegalArgumentException().isThrownBy(reindexer::reindex);
        verifyNoInteractions(destination, embeddings);
        server.verify();
    }
}
