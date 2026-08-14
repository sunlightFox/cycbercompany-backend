package io.github.yourname.cycbercompany.web;

import io.github.yourname.cycbercompany.mod.ModCommand;
import io.github.yourname.cycbercompany.mod.ModSessionService;
import io.github.yourname.cycbercompany.mod.OpenModSessionCommand;
import io.github.yourname.cycbercompany.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mod-sessions")
class ModController {
    private final CurrentActorProvider actors;
    private final ModSessionService sessions;

    ModController(CurrentActorProvider actors, ModSessionService sessions) {
        this.actors = actors;
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object open(@Valid @RequestBody OpenModSessionCommand command, HttpServletRequest request) {
        return sessions.open(command, actors.current(request));
    }

    @GetMapping("/{id}")
    Object get(@PathVariable String id, HttpServletRequest request) {
        return sessions.get(id, actors.current(request));
    }

    @PostMapping("/{id}/commands")
    Object command(@PathVariable String id, @Valid @RequestBody ModCommand command, HttpServletRequest request) {
        return sessions.command(id, command, actors.current(request));
    }
}
