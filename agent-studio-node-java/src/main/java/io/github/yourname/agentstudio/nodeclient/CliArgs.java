package io.github.yourname.agentstudio.nodeclient;

import java.util.HashMap;
import java.util.Map;

final class CliArgs {

    private CliArgs() {
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
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
