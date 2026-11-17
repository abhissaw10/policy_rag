package com.example.policyrag.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class ChatEventWriter {
    private static final Logger EVENTS = LoggerFactory.getLogger("chat.events");
    private final ObjectMapper mapper;
    private final Path file;
    public ChatEventWriter(ObjectMapper mapper, @Value("$" + "{app.observability.events-file:logs/chat-events.jsonl}") String file) {
        this.mapper = mapper;
        this.file = Path.of(file);
    }
    @EventListener
    public synchronized void write(ChatEvent event) {
        String json = mapper.writeValueAsString(event);
        EVENTS.info("{}", json);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            EVENTS.error("Could not append chat event to {} ({})", file, exception.getClass().getSimpleName());
        }
    }
}
