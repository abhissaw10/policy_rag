package com.example.policyrag.service;

import com.example.policyrag.store.PolicyChunk;
import com.example.policyrag.store.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyIngestionServiceTest {

    @Test
    void validatesChunksEmbedsAndStoresTheDocument() {
        UploadValidator validator = mock(UploadValidator.class);
        PdfTextExtractor extractor = mock(PdfTextExtractor.class);
        SemanticChunker chunker = mock(SemanticChunker.class);
        VectorStore store = mock(VectorStore.class);
        byte[] bytes = "%PDF-".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "leave.pdf", "application/pdf", bytes);

        when(validator.validate("E123", "checksum", file)).thenReturn(bytes);
        when(extractor.extract(bytes)).thenReturn("Leave policy text");
        when(chunker.chunk("Leave policy text")).thenReturn(List.of("Leave eligibility", "Approval process"));

        PolicyIngestionService service = new PolicyIngestionService(
                validator, extractor, chunker, new EmbeddingService(), store);

        assertThat(service.ingest("E123", "checksum", file)).isEqualTo(2);
        verify(store).addAll(anyList());
    }
}
