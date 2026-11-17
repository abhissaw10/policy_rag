package com.example.policyrag.chat;

import com.example.policyrag.observability.ChatTrace;
import com.example.policyrag.service.EmbeddingService;
import com.example.policyrag.store.RetrievedChunk;
import com.example.policyrag.store.VectorStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PolicyParagraphsTest {
    private final VectorStore store = mock(VectorStore.class);
    private final EmbeddingService embeddings = new EmbeddingService();
    private final PolicyParagraphs paragraphs = new PolicyParagraphs(store, embeddings, new PromptGuard());
    private final ChatTrace trace = new ChatTrace(event -> {}, "session", "E2345");

    @Test
    void ranksTheMaternityBodyWithItsHeadingAsContextInsteadOfOfferingTheHeadingAsAnAnswer() {
        String body = "Maternity leave must be taken in one continuous period without splitting.";
        var hit = new RetrievedChunk("document-0", "E1234", "leave.pdf", 0,
                "4.5 Maternity Leave\n\n" + body + "\n\n4.6 Paternity Leave\n\nPaternity leave is seven days.", .8);
        when(store.documentChunks("document", "leave.pdf")).thenReturn(List.of(hit));
        var candidates = paragraphs.candidates(List.of(hit), embeddings.embedQuery("Can maternity leave be split?"), trace);
        assertThat(candidates).allSatisfy(p -> assertThat(PolicyParagraphs.isHeading(p.text())).isFalse());
        assertThat(candidates).anySatisfy(p -> {
            assertThat(p.text()).isEqualTo(body);
            assertThat(p.heading()).isEqualTo("4.5 Maternity Leave");
        });
    }

    @Test
    void doesNotOfferStandaloneHeadingsOrLoseTheHeadingContextForLaterParagraphs() {
        String rule = "The company provides one continuous period without splitting.";
        String exception = "Any more favorable statutory entitlement takes precedence.";
        var hit = new RetrievedChunk("document-0", "E1234", "leave.pdf", 0,
                "4.5 Maternity Leave\n\n" + rule + "\n\n" + exception, .8);
        when(store.documentChunks("document", "leave.pdf")).thenReturn(List.of(hit));
        var candidates = paragraphs.candidates(List.of(hit), embeddings.embedQuery("maternity leave"), trace);
        assertThat(candidates).hasSize(1).allSatisfy(p -> {
            assertThat(p.heading()).isEqualTo("4.5 Maternity Leave");
            assertThat(p.text()).contains(rule, exception);
        });
    }

    @Test
    void distinguishesHeadingsFromShortButSubstantiveRulesAndTableRows() {
        assertThat(PolicyParagraphs.isHeading("4.5 Maternity Leave")).isTrue();
        assertThat(PolicyParagraphs.isHeading("Maternity Leave")).isTrue();
        assertThat(PolicyParagraphs.isHeading("Maternity leave cannot be split.")).isFalse();
        assertThat(PolicyParagraphs.isHeading("Annual Leave Maximum 15 business days per financial year")).isFalse();
        assertThat(PolicyParagraphs.isHeading("3 consecutive calendar months of paid Maternity Leave")).isFalse();
        assertThat(PolicyParagraphs.isHeading("1.25 business days for each completed month")).isFalse();
    }
}
