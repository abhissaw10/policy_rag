package com.example.policyrag.observability;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Request/response messages are separate events so an external evaluator can correlate them by traceId. */
public record ChatEvent(String eventType, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp, String sessionId, String traceId,
                        String spanId, String parentSpanId, String toolCall, String employeeId,
                        String message, String status, long durationMs, Map<String, Object> attributes) {
}
