package io.github.yourname.cycbercompany.agent;

import java.util.List;

public class AgentEvaluationRequiredException extends RuntimeException {

    private final List<String> problems;

    public AgentEvaluationRequiredException(List<String> problems) {
        super("Agent evaluation requirements are not satisfied.");
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
