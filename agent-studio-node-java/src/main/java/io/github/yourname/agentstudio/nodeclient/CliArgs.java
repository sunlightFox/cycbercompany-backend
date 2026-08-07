package io.github.yourname.agentstudio.nodeclient;

import java.util.HashMap;
import java.util.Map;

final class CliArgs {

    private CliArgs() {
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        // The packaged launcher places user-supplied options before its configured `gui`
        // argument. Parse from the first option in that form, while retaining the normal
        // command-first CLI convention.
        int firstOption = args.length > 0 && args[0].startsWith("--") ? 0 : 1;
        for (int i = firstOption; i < args.length; i++) {
            String current = args[i];
            if (!current.startsWith("--")) {
                continue;
            }
            String key = current.substring(2);
            String value = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            result.put(key, value);
        }
        return result;
    }
}
