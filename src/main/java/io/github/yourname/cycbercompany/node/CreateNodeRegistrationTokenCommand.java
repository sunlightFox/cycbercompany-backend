package io.github.yourname.cycbercompany.node;

public record CreateNodeRegistrationTokenCommand(Integer ttlSeconds, Boolean persistent) {
}
