package io.github.yourname.agentstudio.tool;

public record RegisteredTool(String name, String description, RiskLevel riskLevel, boolean requiresApproval) {
}
