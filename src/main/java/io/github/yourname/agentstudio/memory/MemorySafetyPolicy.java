package io.github.yourname.agentstudio.memory;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MemorySafetyPolicy {

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|api[_ -]?key|secret|access[_ -]?token|private[_ -]?key)\\s*[:=]\\s*\\S+");
    private static final Pattern LONG_DIGIT_SEQUENCE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");

    public void validateUserMemory(String content) {
        String value = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (SECRET_ASSIGNMENT.matcher(value).find() || LONG_DIGIT_SEQUENCE.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "Memory content appears to contain a credential or payment identifier and cannot be saved.");
        }
    }
}
