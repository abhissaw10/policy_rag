package com.example.policyrag.api;

import com.example.policyrag.chat.PolicyChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {
    private final PolicyChatService chat;
    public ChatController(PolicyChatService chat) { this.chat = chat; }
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request, HttpServletRequest http) {
        ChatResponse response = chat.chat(request, http.getSession(true).getId());
        HttpStatus status = switch (response.status()) {
            case INVALID, BLOCKED, OUT_OF_SCOPE -> HttpStatus.BAD_REQUEST;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).header("X-Trace-Id", response.traceId()).body(response);
    }
}
