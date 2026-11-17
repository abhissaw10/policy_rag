package com.example.policyrag.chat;

import com.example.policyrag.store.PoliciesUpdatedEvent;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatCacheTest {
    @Test
    void expiresCachedResponsesAfterTenMinutes() {
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-11-16T00:00:00Z");
        when(clock.instant()).thenReturn(now, now.plus(Duration.ofMinutes(11)));
        ChatCache cache = new ChatCache(clock);
        cache.put("question", 0, new ChatCache.Answer(ChatStatus.NOT_FOUND, "No answer found.", null));
        assertThat(cache.get("question", 0)).isNull();
    }
    @Test
    void rejectsInFlightResultsFromBeforeAnUploadAndBoundsStorage() {
        ChatCache cache = new ChatCache();
        var answer = new ChatCache.Answer(ChatStatus.NOT_FOUND, "No answer found.", null);
        long oldRevision = cache.revision();
        cache.invalidate(new PoliciesUpdatedEvent("E9999"));
        cache.put("question", oldRevision, answer);
        assertThat(cache.get("question", cache.revision())).isNull();
        for (int index = 0; index < 1001; index++) cache.put("q" + index, 1, answer);
        assertThat(cache.get("q0", 1)).isNull();
        assertThat(cache.get("q1000", 1)).isEqualTo(answer);
    }
}
