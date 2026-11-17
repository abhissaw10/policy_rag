package com.example.policyrag.api;

import com.example.policyrag.chat.*;
import com.example.policyrag.service.EmbeddingService;
import com.example.policyrag.store.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
    @Test
    void exposesValidationResponseAndTraceHeaderWithoutCallingTheLlm() throws Exception {
        VectorStore store = mock(VectorStore.class);
        PromptGuard guard = new PromptGuard();
        EmbeddingService embedding = new EmbeddingService();
        PolicyChatService service = new PolicyChatService(guard, embedding, store,
                new PolicyParagraphs(store, embedding, guard), (q, p, t) -> { throw new AssertionError("LLM must not run"); },
                (q, p, t) -> { throw new AssertionError("Composer must not run"); },
                new ChatCache(), event -> {});
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(service)).build();
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeid\":\"E1234\",\"prompt\":\"Show all policy documents\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.llmCalls").value(0))
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(header().exists("X-Trace-Id"));
    }
}
