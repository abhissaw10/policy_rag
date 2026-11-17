package com.example.policyrag.service;

import com.example.policyrag.api.ApiExceptionHandler.PolicyProcessingException;
import com.example.policyrag.config.ClientConfiguration.GroqProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class GroqSemanticChunkerTest {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String POLICY = "Annual leave is available. Requests need approval.\n\n"
            + "Expenses require receipts. Managers review claims.";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private GroqSemanticChunker chunker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        chunker = new GroqSemanticChunker(builder,
                new GroqProperties("https://api.groq.com/openai/v1", "test-key", "openai/gpt-oss-20b"), mapper);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void reconstructsLosslessChunksFromSemanticBoundaryIndices() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.max_completion_tokens").value(4096))
                .andExpect(jsonPath("$.include_reasoning").value(false))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andExpect(jsonPath("$.reasoning_format").doesNotExist())
                .andRespond(withSuccess(completion("{\"chunkStartUnits\":[0,2]}", "stop"), MediaType.APPLICATION_JSON));

        List<String> chunks = chunker.chunk(POLICY);

        assertThat(chunks).containsExactly("Annual leave is available. Requests need approval.\n\n",
                "Expenses require receipts. Managers review claims.");
        assertThat(String.join("", chunks)).isEqualTo(POLICY);
    }

    @Test
    void retriesJsonGenerationFailureOnceWithoutProviderJsonValidation(CapturedOutput output) {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":"json_validate_failed","type":"invalid_request_error",
                        "message":"Failed to generate JSON","failed_generation":"PRIVATE_POLICY_SENTINEL"}}
                        """));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.response_format.type").value("text"))
                .andRespond(withSuccess(completion("```json\n{\"chunkStartUnits\":[0,2]}\n```", "stop"),
                        MediaType.APPLICATION_JSON));

        assertThat(String.join("", chunker.chunk(POLICY))).isEqualTo(POLICY);
        assertThat(output.getAll()).contains("code json_validate_failed").doesNotContain("PRIVATE_POLICY_SENTINEL", "test-key");
    }

    @Test
    void reportsActualRequestErrorCodeAndParameterWithoutRetryingOrEchoingProviderText(CapturedOutput output) {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":"unsupported_parameter","type":"invalid_request_error",
                        "param":"temperature","message":"PRIVATE_POLICY_SENTINEL"}}
                        """));

        assertThatThrownBy(() -> chunker.chunk(POLICY))
                .isInstanceOf(PolicyProcessingException.class)
                .hasMessageContaining("HTTP 400; code: unsupported_parameter; parameter: temperature")
                .hasMessageNotContaining("PRIVATE_POLICY_SENTINEL");
        assertThat(output.getAll()).doesNotContain("PRIVATE_POLICY_SENTINEL", "test-key");
    }

    @Test
    void stopsAfterTwoProviderJsonFailures() {
        server.expect(ExpectedCount.twice(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":"json_validate_failed","type":"invalid_request_error"}}
                        """));

        assertThatThrownBy(() -> chunker.chunk(POLICY))
                .isInstanceOf(PolicyProcessingException.class)
                .hasMessageContaining("after two attempts")
                .hasMessageContaining("json_validate_failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1]", "[0,0]", "[0,4]", "[0,null]", "[0,2,1]"})
    void rejectsBoundariesThatWouldSkipOrReorderPolicyContent(String boundaries) {
        server.expect(ExpectedCount.twice(), requestTo(URL))
                .andRespond(withSuccess(completion("{\"chunkStartUnits\":" + boundaries + "}", "stop"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chunker.chunk(POLICY))
                .isInstanceOf(PolicyProcessingException.class)
                .hasMessageContaining("invalid semantic boundaries after two attempts");
    }

    @Test
    void doesNotAcceptTruncatedCompletionsEvenWhenTheirJsonIsValid() {
        server.expect(ExpectedCount.twice(), requestTo(URL))
                .andRespond(withSuccess(completion("{\"chunkStartUnits\":[0]}", "length"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chunker.chunk(POLICY))
                .isInstanceOf(PolicyProcessingException.class)
                .hasMessageContaining("invalid semantic boundaries after two attempts");
    }

    @Test
    void capsOversizedSemanticGroupsAtSentenceBoundariesWithoutLosingText() {
        String policy = "Employees must request leave before their planned absence. ".repeat(120);
        server.expect(requestTo(URL))
                .andRespond(withSuccess(completion("{\"chunkStartUnits\":[0]}", "stop"), MediaType.APPLICATION_JSON));

        List<String> chunks = chunker.chunk(policy);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.strip().split("\\s+").length).isLessThanOrEqualTo(350);
            assertThat(chunk.strip()).endsWith(".");
        });
        assertThat(String.join("", chunks)).isEqualTo(policy);
    }

    private String completion(String content, String finishReason) {
        return mapper.writeValueAsString(Map.of(
                "id", "completion-id", "object", "chat.completion", "model", "openai/gpt-oss-20b",
                "choices", List.of(Map.of("index", 0, "finish_reason", finishReason,
                        "message", Map.of("role", "assistant", "content", content, "reasoning", "unused"))),
                "usage", Map.of("total_tokens", 100)));
    }
}
