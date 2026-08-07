package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.persona.CreateUserPersonaCommand;
import io.github.yourname.agentstudio.persona.UpdateUserPersonaCommand;
import io.github.yourname.agentstudio.persona.UserPersonaService;
import io.github.yourname.agentstudio.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/personas")
class UserPersonaController {

    private final CurrentActorProvider actors;
    private final UserPersonaService personas;

    UserPersonaController(CurrentActorProvider actors, UserPersonaService personas) {
        this.actors = actors;
        this.personas = personas;
    }

    @GetMapping
    Object list(HttpServletRequest request) {
        return personas.list(actors.current(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody CreateUserPersonaCommand command, HttpServletRequest request) {
        return personas.create(command, actors.current(request));
    }

    @GetMapping("/{id}")
    Object get(@PathVariable String id, HttpServletRequest request) {
        return personas.get(id, actors.current(request));
    }

    @PatchMapping("/{id}")
    Object update(
            @PathVariable String id,
            @Valid @RequestBody UpdateUserPersonaCommand command,
            HttpServletRequest request) {
        return personas.update(id, command, actors.current(request));
    }

    @PostMapping("/{id}/default")
    Object setDefault(@PathVariable String id, HttpServletRequest request) {
        return personas.setDefault(id, actors.current(request));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        personas.delete(id, actors.current(request));
        return ResponseEntity.noContent().build();
    }
}
