package com.example.policyrag.chat;

import com.example.policyrag.store.PoliciesUpdatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChatCache {
    private static final int MAX_ENTRIES = 1000;
    private final Clock clock;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long revision;
    public ChatCache() { this(Clock.systemUTC()); }
    ChatCache(Clock clock) { this.clock = clock; }
    public synchronized long revision() { return revision; }
    public synchronized Answer get(String prompt, long revision) {
        Key key = new Key(prompt, revision);
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt().isBefore(clock.instant())) { entries.remove(key); return null; }
        return entry.answer();
    }
    public synchronized void put(String prompt, long revision, Answer answer) {
        if (revision != this.revision) return;
        entries.put(new Key(prompt, revision), new Entry(answer, clock.instant().plus(Duration.ofMinutes(10))));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
    }
    @EventListener
    public synchronized void invalidate(PoliciesUpdatedEvent event) {
        revision++;
        entries.clear();
    }
    public record Answer(ChatStatus status, String message, String filename) { }
    private record Key(String prompt, long revision) { }
    private record Entry(Answer answer, Instant expiresAt) { }
}
