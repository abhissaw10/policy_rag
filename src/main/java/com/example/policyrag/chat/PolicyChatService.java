package com.example.policyrag.chat;

import com.example.policyrag.api.ApiExceptionHandler.PolicyProcessingException;
import com.example.policyrag.api.ChatRequest;
import com.example.policyrag.api.ChatResponse;
import com.example.policyrag.observability.ChatTrace;
import com.example.policyrag.service.EmbeddingService;
import com.example.policyrag.store.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class PolicyChatService {
    private final PromptGuard guard;
    private final EmbeddingService embeddings;
    private final VectorStore store;
    private final PolicyParagraphs paragraphs;
    private final ParagraphSelector selector;
    private final AnswerComposer composer;
    private final ChatCache cache;
    private final ApplicationEventPublisher events;
    public PolicyChatService(PromptGuard guard, EmbeddingService embeddings, VectorStore store,
                             PolicyParagraphs paragraphs, ParagraphSelector selector, AnswerComposer composer, ChatCache cache,
                             ApplicationEventPublisher events) {
        this.guard = guard;
        this.embeddings = embeddings;
        this.store = store;
        this.paragraphs = paragraphs;
        this.selector = selector;
        this.composer = composer;
        this.cache = cache;
        this.events = events;
    }

    public ChatResponse chat(ChatRequest request, String sessionId) {
        String employeeId = request.employeeId() == null ? null : request.employeeId().strip();
        ChatTrace trace = new ChatTrace(events, sessionId, employeeId);
        trace.request(request.prompt());
        try {
            PromptGuard.Rejection rejection = trace.tool("prompt.validate", () -> guard.validate(employeeId, request.prompt()));
            if (rejection != null) return finish(new ChatCache.Answer(rejection.status(), rejection.message(), null), false, trace);
            String prompt = guard.normalized(request.prompt());
            long revision = cache.revision();
            ChatCache.Answer cached = trace.tool("cache.lookup", () -> cache.get(prompt, revision));
            if (cached != null) return finish(cached, true, trace);
            trace.attribute("embeddingModel", EmbeddingService.MODEL_ID);
            float[] vector = trace.tool("embedding.embed", () -> embeddings.embedQuery(prompt));
            var hits = trace.tool("chroma.similarity.query", () -> store.search(vector, 8));
            trace.attribute("retrievedChunks", hits.size());
            if (hits.isEmpty()) {
                trace.attribute("candidateParagraphs", 0);
                trace.attribute("evidenceReason", "EMPTY_POLICY_COLLECTION");
                return finish(new ChatCache.Answer(ChatStatus.NO_POLICIES,
                        "No HR policies are indexed yet. Upload a policy from the upload page, then ask your question.",
                        null), false, trace);
            }
            List<PolicyParagraphs.Paragraph> evidence = trace.tool("paragraphs.reconstruct",
                    () -> paragraphs.candidates(hits, vector, trace));
            trace.attribute("candidateParagraphs", evidence.size());
            if (evidence.isEmpty()) trace.attribute("evidenceReason", "NO_USABLE_PARAGRAPHS");
            ChatCache.Answer answer;
            // With indexed policies, let the selector distinguish missing HR answers from unrelated questions.
            ParagraphSelector.Selection selection = selector.select(prompt, evidence, trace);
            if (selection.decision() == ChatStatus.ANSWER) {
                PolicyParagraphs.Paragraph selected = evidence.stream()
                        .filter(paragraph -> paragraph.id().equals(selection.paragraphId())).findFirst().orElse(null);
                if (selected == null || PolicyParagraphs.isHeading(selected.text()) || guard.unsafe(selected.text())) {
                    answer = new ChatCache.Answer(ChatStatus.NOT_FOUND, "I could not find a verified answer in the HR policy documents.", null);
                } else {
                    AnswerComposer.Composition result = composer.compose(prompt, selected, trace);
                    if (result == null) {
                        answer = new ChatCache.Answer(ChatStatus.NOT_FOUND,
                                "I could not verify an answer that directly addresses your question in the policy text.", null);
                    } else if (result.text().codePointCount(0, result.text().length()) > 250) {
                        answer = new ChatCache.Answer(ChatStatus.LIMIT_EXCEEDED,
                                "A verified answer and its PDF filename could not fit within 250 characters.", selected.filename());
                    } else {
                        answer = new ChatCache.Answer(ChatStatus.ANSWER, result.text(), selected.filename());
                    }
                }
            } else {
                answer = switch (selection.decision()) {
                    case BLOCKED -> new ChatCache.Answer(ChatStatus.BLOCKED, "Please ask one specific HR policy question without instruction overrides.", null);
                    case OUT_OF_SCOPE -> new ChatCache.Answer(ChatStatus.OUT_OF_SCOPE, "I can only answer questions about the indexed HR policy documents.", null);
                    default -> new ChatCache.Answer(ChatStatus.NOT_FOUND, "I could not find an answer to that question in the HR policy documents.", null);
                };
            }
            if (answer.status() != ChatStatus.BLOCKED && answer.status() != ChatStatus.OUT_OF_SCOPE
                    && !(selection.decision() == ChatStatus.ANSWER && answer.status() != ChatStatus.ANSWER)) {
                ChatCache.Answer finalAnswer = answer;
                trace.tool("cache.store", () -> { cache.put(prompt, revision, finalAnswer); return true; });
            }
            return finish(answer, false, trace);
        } catch (PolicyProcessingException exception) {
            return finish(new ChatCache.Answer(ChatStatus.UNAVAILABLE, exception.getMessage(), null), false, trace);
        } catch (RuntimeException exception) {
            return finish(new ChatCache.Answer(ChatStatus.UNAVAILABLE, "The policy assistant is temporarily unavailable. Please try again.", null), false, trace);
        }
    }

    private ChatResponse finish(ChatCache.Answer answer, boolean cached, ChatTrace trace) {
        String message = answer.message();
        if (message.codePointCount(0, message.length()) > 250) {
            message = "The policy assistant could not return an answer within the 250-character limit.";
        }
        trace.response(message, answer.status().name(), cached);
        return new ChatResponse(answer.status(), message, answer.filename(), cached, trace.sessionId(), trace.traceId(), trace.llmCalls());
    }
}
