package com.example.policyrag.api;

import com.example.policyrag.chat.ChatStatus;

public record ChatResponse(ChatStatus status, String message, String filename, boolean cached,
                           String sessionId, String traceId, int llmCalls) {
}
