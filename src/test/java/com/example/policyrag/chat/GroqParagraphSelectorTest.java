package com.example.policyrag.chat;

import com.example.policyrag.api.ApiExceptionHandler.PolicyProcessingException;
import com.example.policyrag.config.ClientConfiguration.GroqProperties;
import com.example.policyrag.observability.ChatTrace;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GroqParagraphSelectorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GroqParagraphSelector selector = new GroqParagraphSelector(builder,
            new GroqProperties("https://groq.test", "test-key", "openai/gpt-oss-20b"), mapper);
    private final ChatTrace trace = new ChatTrace(event -> {}, "session", "E1234");
    private final List<PolicyParagraphs.Paragraph> evidence = List.of(
            new PolicyParagraphs.Paragraph("p0", "leave.pdf", "Annual leave is 20 days.", .8));

    @Test
    void selectsOnlyAnExistingParagraphWithBoundedJsonRecovery() {
        server.expect(requestTo("https://groq.test/chat/completions"))
                .andExpect(jsonPath("$.max_completion_tokens").value(150))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"json_validate_failed\"}}"));
        server.expect(requestTo("https://groq.test/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("text"))
                .andExpect(jsonPath("$.max_completion_tokens").value(150))
                .andRespond(withSuccess(completion("p0"), MediaType.APPLICATION_JSON));
        assertThat(selector.select("How much annual leave?", evidence, trace).paragraphId()).isEqualTo("p0");
        assertThat(trace.llmCalls()).isEqualTo(2);
        server.verify();
    }
    @Test
    void rejectsFabricatedSourceIdsInsteadOfReturningGeneratedAnswers() {
        server.expect(ExpectedCount.twice(), requestTo("https://groq.test/chat/completions"))
                .andExpect(jsonPath("$.max_completion_tokens").value(150))
                .andRespond(withSuccess(completion("invented-source"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> selector.select("How much annual leave?", evidence, trace))
                .isInstanceOf(PolicyProcessingException.class);
        assertThat(trace.llmCalls()).isEqualTo(2);
        server.verify();
    }
    @Test
    void refusesASixthLlmCall() {
        for (int count = 0; count < 5; count++) trace.beforeLlmCall();
        assertThatThrownBy(trace::beforeLlmCall).isInstanceOf(IllegalStateException.class);
        assertThat(trace.llmCalls()).isEqualTo(5);
    }
    @Test
    void rejectsAHeadingEvenIfTheModelClaimsItAnswersTheQuestion() {
        server.expect(ExpectedCount.twice(), requestTo("https://groq.test/chat/completions"))
                .andExpect(jsonPath("$.max_completion_tokens").value(150))
                .andRespond(withSuccess(completion("p0"), MediaType.APPLICATION_JSON));
        var headings = List.of(new PolicyParagraphs.Paragraph("p0", "leave.pdf", "4.5 Maternity Leave", .9));
        assertThatThrownBy(() -> selector.select("Can maternity leave be split?", headings, trace))
                .isInstanceOf(PolicyProcessingException.class);
        server.verify();
    }
    private String completion(String id) {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", mapper.writeValueAsString(Map.of("decision", "ANSWER", "paragraphId", id,
                        "rankedParagraphIds", List.of(id))))))));
    }
}
