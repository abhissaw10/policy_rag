package com.example.policyrag.chat;

import com.example.policyrag.observability.ChatTrace;
import java.util.List;

public interface ParagraphSelector {
    Selection select(String prompt, List<PolicyParagraphs.Paragraph> paragraphs, ChatTrace trace);
    record Selection(ChatStatus decision, String paragraphId, List<String> rankedParagraphIds) {
        public Selection(ChatStatus decision, String paragraphId) {
            this(decision, paragraphId, paragraphId == null ? List.of() : List.of(paragraphId));
        }
    }
}
