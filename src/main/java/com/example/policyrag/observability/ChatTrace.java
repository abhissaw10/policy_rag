package com.example.policyrag.observability;

import org.springframework.context.ApplicationEventPublisher;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;

public final class ChatTrace {
    private final ApplicationEventPublisher events;
    private final String sessionId;
    private final String employeeId;
    private final String traceId = UUID.randomUUID().toString().replace("-", "");
    private final String rootSpan = spanId();
    private String activeSpan = rootSpan;
    private final long started = System.nanoTime();
    private int llmCalls;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public ChatTrace(ApplicationEventPublisher events, String sessionId, String employeeId) {
        this.events = events;
        this.sessionId = sessionId;
        this.employeeId = employeeId;
    }
    public void request(String prompt) {
        publish("chat.request", rootSpan, null, null, prompt, "STARTED", 0, Map.of());
    }
    public void response(String message, String status, boolean cached) {
        Map<String, Object> attributes = new LinkedHashMap<>(details);
        attributes.put("cached", cached);
        attributes.put("llmCalls", llmCalls);
        publish("chat.response", rootSpan, null, null, message, status, elapsed(started),
                Map.copyOf(attributes));
    }
    public void attribute(String name, Object value) { details.put(name, value); }
    public <T> T tool(String name, Supplier<T> action) {
        String span = spanId();
        String parent = activeSpan;
        long start = System.nanoTime();
        publish("tool.started", span, parent, name, null, "STARTED", 0, Map.of());
        activeSpan = span;
        try {
            T result = action.get();
            publish("tool.completed", span, parent, name, null, "OK", elapsed(start), Map.of());
            return result;
        } catch (RuntimeException exception) {
            publish("tool.completed", span, parent, name, null, "ERROR", elapsed(start),
                    Map.of("errorType", exception.getClass().getSimpleName()));
            throw exception;
        } finally {
            activeSpan = parent;
        }
    }
    public void beforeLlmCall() {
        if (llmCalls >= 5) throw new IllegalStateException("Maximum of five LLM calls per chat request exceeded");
        llmCalls++;
    }
    public String sessionId() { return sessionId; }
    public String traceId() { return traceId; }
    public int llmCalls() { return llmCalls; }
    public int remainingLlmCalls() { return 5 - llmCalls; }
    private void publish(String event, String span, String parent, String tool, String message,
                         String status, long duration, Map<String, Object> attributes) {
        events.publishEvent(new ChatEvent(event, Instant.now(), sessionId, traceId, span, parent, tool,
                employeeId, message, status, duration, attributes));
    }
    private static String spanId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
