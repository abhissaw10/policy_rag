package com.example.policyrag.chat;

import com.example.policyrag.api.ApiExceptionHandler.PolicyProcessingException;
import com.example.policyrag.config.ClientConfiguration.GroqProperties;
import com.example.policyrag.observability.ChatTrace;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GroqJsonClient {
    private final RestClient client;
    private final GroqProperties properties;
    private final ObjectMapper mapper;
    public GroqJsonClient(RestClient.Builder builder, GroqProperties properties, ObjectMapper mapper) {
        client = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.mapper = mapper;
    }
    public JsonNode complete(String instructions, Map<String, Object> input, String tool, ChatTrace trace, int maxAttempts) {
        int attempts = Math.min(maxAttempts, trace.remainingLlmCalls());
        boolean incompleteCompletion = false;
        int generatedCharacters = 0;
        for (int attempt = 0; attempt < attempts; attempt++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.model());
            body.put("messages", List.of(Map.of("role", "system", "content", instructions),
                    Map.of("role", "user", "content", mapper.writeValueAsString(input))));
            body.put("response_format", Map.of("type", attempt == 0 ? "json_object" : "text"));
            body.put("temperature", .1);
            body.put("max_completion_tokens", 150);
            if (properties.model().startsWith("openai/gpt-oss-")) {
                body.put("include_reasoning", false);
                body.put("reasoning_effort", "low");
            }
            try {
                trace.beforeLlmCall();
                JsonNode response = trace.tool(tool, () -> client.post().uri("/chat/completions")
                        .header("Authorization", "Bearer " + properties.apiKey()).contentType(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().body(JsonNode.class));
                if (response == null || !response.path("choices").isArray() || response.path("choices").isEmpty()) {
                    incompleteCompletion = true;
                    continue;
                }
                JsonNode choice = response.path("choices").get(0);
                String content = choice.path("message").path("content").asString("").strip();
                generatedCharacters = content.codePointCount(0, content.length());
                if ("length".equals(choice.path("finish_reason").asString(""))) {
                    incompleteCompletion = true;
                    continue;
                }
                if (content.startsWith("\u0060\u0060\u0060") && content.endsWith("\u0060\u0060\u0060") && content.indexOf('\n') >= 0) {
                    content = content.substring(content.indexOf('\n') + 1, content.length() - 3).strip();
                }
                try {
                    JsonNode result = mapper.readTree(content);
                    if (result != null && result.isObject()) return result;
                } catch (RuntimeException ignored) { }
                incompleteCompletion = true;
            } catch (RestClientResponseException exception) {
                String code = "";
                try { code = mapper.readTree(exception.getResponseBodyAsString()).path("error").path("code").asString(""); }
                catch (RuntimeException ignored) { }
                if (exception.getStatusCode().value() == 400 && "json_validate_failed".equals(code)) {
                    incompleteCompletion = true;
                    continue;
                }
                throw new PolicyProcessingException("The policy assistant is temporarily unavailable. Please try again.", exception);
            } catch (RestClientException exception) {
                throw new PolicyProcessingException("The policy assistant is temporarily unavailable. Please try again.", exception);
            }
        }
        if (incompleteCompletion) throw new IncompleteCompletionException(generatedCharacters);
        throw new PolicyProcessingException("The policy assistant could not verify an answer within its LLM call budget.");
    }

    static final class IncompleteCompletionException extends RuntimeException {
        private final int generatedCharacters;

        IncompleteCompletionException(int generatedCharacters) {
            super("The model response was truncated or was not valid JSON.");
            this.generatedCharacters = generatedCharacters;
        }

        int generatedCharacters() { return generatedCharacters; }
    }
}
