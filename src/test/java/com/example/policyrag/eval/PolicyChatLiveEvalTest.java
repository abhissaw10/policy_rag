package com.example.policyrag.eval;

import com.example.policyrag.api.ChatRequest;
import com.example.policyrag.chat.*;
import com.example.policyrag.config.ClientConfiguration.ChromaProperties;
import com.example.policyrag.config.ClientConfiguration.GroqProperties;
import com.example.policyrag.observability.ChatEvent;
import com.example.policyrag.observability.ChatEventWriter;
import com.example.policyrag.service.EmbeddingService;
import com.example.policyrag.store.ChromaVectorStore;
import com.example.policyrag.store.PolicyChunk;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in integration evals: real Chroma, the Part 1 embedding service, and the configured Groq LLM. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_EVALS", matches = "true")
class PolicyChatLiveEvalTest {
    private static final String PARAGRAPH = "Employees receive 20 days of annual leave each year.";
    private static final String MATERNITY = "The company baseline is 3 consecutive calendar months of paid Maternity Leave, "
            + "taken in one continuous period without splitting. Where applicable maternity-benefit law provides "
            + "a longer duration, different commencement rule or other protection, the statutory entitlement "
            + "applies and is not reduced by this policy.";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String collection = "eval_chat_" + UUID.randomUUID().toString().replace("-", "");
    private RestClient chroma;
    private PolicyChatService service;
    private boolean created;

    @BeforeAll
    void setUp() {
        String key = System.getenv("GROQ_API_KEY");
        assertThat(key).as("GROQ_API_KEY is required for live evals").isNotBlank();
        String baseUrl = System.getenv().getOrDefault("CHROMA_URL", "http://localhost:8000");
        chroma = RestClient.builder().baseUrl(baseUrl).build();
        ChromaProperties chromaProperties = new ChromaProperties(baseUrl, "default_tenant", "default_database", collection);
        ChatEventWriter writer = new ChatEventWriter(mapper, "target/evals/chat-events.jsonl");
        ApplicationEventPublisher events = event -> { if (event instanceof ChatEvent chat) writer.write(chat); };
        EmbeddingService embedding = new EmbeddingService();
        ChromaVectorStore store = new ChromaVectorStore(RestClient.builder(), chromaProperties, events);
        String document = UUID.randomUUID().toString();
        String approval = "A manager must approve annual leave requests before the absence.";
        store.addAll(List.of(
                new PolicyChunk(document + "-0", "E9001", "leave-policy.pdf", 0, PARAGRAPH + "\n\n", embedding.embedDocument(PARAGRAPH)),
                new PolicyChunk(document + "-1", "E9001", "leave-policy.pdf", 1, approval, embedding.embedDocument(approval))));
        created = true;
        String maternityDocument = UUID.randomUUID().toString();
        store.addAll(List.of(
                new PolicyChunk(maternityDocument + "-0", "E9001", "maternity-policy.pdf", 0,
                        "4.5 Maternity Leave\n\n", embedding.embedDocument("4.5 Maternity Leave")),
                new PolicyChunk(maternityDocument + "-1", "E9001", "maternity-policy.pdf", 1,
                        MATERNITY.replace(" Where applicable", "\n\nWhere applicable"), embedding.embedDocument(MATERNITY))));
        PromptGuard guard = new PromptGuard();
        var groq = new GroqProperties("https://api.groq.com/openai/v1", key,
                System.getenv().getOrDefault("GROQ_MODEL", "openai/gpt-oss-20b"));
        var selector = new GroqParagraphSelector(RestClient.builder(), groq, mapper);
        service = new PolicyChatService(guard, embedding, store, new PolicyParagraphs(store, embedding, guard),
                selector, new GroundedAnswerComposer(new GroqJsonClient(RestClient.builder(), groq, mapper) {
                    @Override
                    public tools.jackson.databind.JsonNode complete(String instructions, Map<String, Object> input,
                            String tool, com.example.policyrag.observability.ChatTrace trace, int attempts) {
                        var draft = super.complete(instructions, input, tool, trace, attempts);
                        if ("groq.answer.summarize".equals(tool)) System.out.println("EVAL_DRAFT " + draft);
                        return draft;
                    }
                }, guard),
                new ChatCache(), events);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "answer_found, How many days of annual leave do employees receive?, ANSWER",
            "answer_missing, How much sabbatical leave can employees take?, NOT_FOUND",
            "outside_documents, What is the capital of France?, OUT_OF_SCOPE",
            "maternity_summary, Can I split maternity leave into two separate tranches?, ANSWER"
    })
    void evaluatesRequestedScenarios(String scenario, String prompt, ChatStatus expected) {
        // Fixtures were uploaded by E9001; E9002 must be able to search the same shared library.
        var response = service.chat(new ChatRequest("E9002", prompt), "live-eval-session");
        System.out.println("EVAL_RESULT " + mapper.writeValueAsString(Map.of("scenario", scenario,
                "expected", expected, "actual", response.status(), "traceId", response.traceId(),
                "llmCalls", response.llmCalls(), "message", response.message())));
        assertThat(response.status()).isEqualTo(expected);
        assertThat(response.message().codePointCount(0, response.message().length())).isLessThanOrEqualTo(250);
        assertThat(response.llmCalls()).isBetween(1, 5);
        if (expected == ChatStatus.ANSWER) {
            if ("maternity_summary".equals(scenario)) {
                assertThat(response.message()).startsWith("Summary: ").endsWith("Source: maternity-policy.pdf")
                        .containsPattern("(?i)(cannot|can't|not|no\\b|without splitting|one continuous)")
                        .containsPattern("(?i)(statutory|law|legal)");
                assertThat(response.message()).doesNotContain("4.5 Maternity Leave\nSource:");
                assertThat(response.llmCalls()).isBetween(3, 5);
            } else {
                assertThat(response.message()).isEqualTo(PARAGRAPH + "\nSource: leave-policy.pdf");
            }
            var repeated = service.chat(new ChatRequest("E9003", prompt), "live-eval-session");
            assertThat(repeated.cached()).isTrue();
            assertThat(repeated.llmCalls()).isZero();
        } else {
            assertThat(response.message()).doesNotContain(PARAGRAPH);
        }
    }

    @AfterAll
    void cleanUpOnlyThisEvalCollection() {
        if (created) chroma.delete().uri("/api/v2/tenants/default_tenant/databases/default_database/collections/" + collection)
                .retrieve().toBodilessEntity();
    }
}
