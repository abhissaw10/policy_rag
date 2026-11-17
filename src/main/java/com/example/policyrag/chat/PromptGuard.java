package com.example.policyrag.chat;

import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PromptGuard {
    private static final Pattern SECRET = Pattern.compile(
            "(password|passwd|credential|api.?key|access.?token|private.?key|system.?prompt|system.?level|"
            + "environment.?variable|connection.?string|database.?schema|server.?config|root.?access|"
            + "internal.?instruction|developer.?message|chain.?of.?thought|spanid|traceid)");
    private static final Pattern INJECTION = Pattern.compile(
            "\\b(ignore|disregard|override|forget|bypass)\\b.{0,40}\\b(instructions?|previous|system|safety|prompts?)\\b|"
            + "(?:^|[.!?]\\s+)(?:please\\s+)?(ignore|disregard|override|forget|bypass)\\b.{0,40}(rule|policy)|"
            + "(you (must|should)|i (want|need) you to).{0,15}(ignore|disregard|override|forget|bypass).{0,40}(rule|policy)|"
            + "(act as|pretend|you are now|jailbreak|do anything now|developer mode)|"
            + "(base64|rot13|hex.?encod|decode|obfuscat)|"
            + "(<\\|.*?\\|>|</?(system|assistant|developer)>|\\[inst\\]|\\x60{3})|"
            + "(execute|run).{0,20}(command|shell|script|sql)|"
            + "(reveal|print|show).{0,25}(hidden|instructions|secrets)");
    private static final Pattern BULK = Pattern.compile(
            "\\b(all|every|entire|full|complete|whole)\\b.{0,40}(polic|document|content|handbook|file)|"
            + "(dump|export|download|list).{0,25}(polic|document|content|file)|"
            + "(polic|document|content).{0,25}(all|entirety|verbatim)|"
            + "^((tell|show|give) me (about )?(hr )?(the )?policies|everything|tell me everything|help|hi|hello|policy|policies)[?.! ]*$");
    public String normalized(String prompt) {
        return Normalizer.normalize(prompt, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public Rejection validate(String employeeId, String prompt) {
        if (employeeId == null || employeeId.isBlank() || employeeId.codePointCount(0, employeeId.length()) > 5
                || employeeId.codePoints().anyMatch(Character::isISOControl)) {
            return new Rejection(ChatStatus.INVALID, "Employee ID is required and must not exceed 5 characters.");
        }
        if (prompt == null || prompt.isBlank()) return new Rejection(ChatStatus.INVALID, "Please ask a specific HR policy question.");
        if (prompt.codePointCount(0, prompt.length()) > 100) return new Rejection(ChatStatus.INVALID, "Your question must not exceed 100 characters.");
        if (prompt.codePoints().anyMatch(code -> Character.getType(code) == Character.FORMAT || Character.isISOControl(code))) {
            return new Rejection(ChatStatus.BLOCKED, "Hidden or control characters are not allowed in questions.");
        }
        String text = normalized(prompt);
        if (unsafe(text)) return new Rejection(ChatStatus.BLOCKED, "Requests for secrets, system details, or instruction overrides are not allowed.");
        if (BULK.matcher(text).find() || text.replaceAll("[^\\p{L}\\p{N}]", "").length() < 5) {
            return new Rejection(ChatStatus.BLOCKED, "Please ask one specific question. Requests for entire policies are not allowed.");
        }
        return null;
    }
    public boolean unsafe(String text) {
        String value = normalized(text);
        return SECRET.matcher(value).find() || INJECTION.matcher(value).find();
    }
    public record Rejection(ChatStatus status, String message) { }
}
