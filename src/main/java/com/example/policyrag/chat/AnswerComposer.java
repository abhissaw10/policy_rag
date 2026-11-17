package com.example.policyrag.chat;

import com.example.policyrag.observability.ChatTrace;

public interface AnswerComposer {
    Composition compose(String question, PolicyParagraphs.Paragraph paragraph, ChatTrace trace);
    /** Null means the proposed answer could not be verified against the selected source. */
    record Composition(String text, boolean summarized) { }
}
