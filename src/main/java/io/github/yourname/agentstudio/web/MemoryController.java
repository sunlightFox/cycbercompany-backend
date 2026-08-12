package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.memory.ClearMemoryCommand;
import io.github.yourname.agentstudio.memory.CreateMemoryCommand;
import io.github.yourname.agentstudio.memory.MemoryService;
import io.github.yourname.agentstudio.memory.MemoryOrigin;
import io.github.yourname.agentstudio.memory.MemoryStatus;
import io.github.yourname.agentstudio.memory.MemoryType;
import io.github.yourname.agentstudio.memory.UpdateMemoryCommand;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/memories")
class MemoryController {

    private final CurrentActorProvider actors;
    private final MemoryService memories;

    MemoryController(CurrentActorProvider actors, MemoryService memories) {
        this.actors = actors;
        this.memories = memories;
    }

    @GetMapping
    List<?> list(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String personaId,
            @RequestParam(defaultValue = "false") boolean sharedOnly,
            @RequestParam(required = false) MemoryType type,
            @RequestParam(required = false) MemoryStatus status,
            @RequestParam(required = false) MemoryOrigin origin,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        return memories.list(agentId, personaId, sharedOnly, type, status, origin, query, limit, actors.current(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody CreateMemoryCommand command, HttpServletRequest request) {
        return memories.create(command, actors.current(request));
    }

    @PatchMapping("/{id}")
    Object update(
            @PathVariable String id,
            @Valid @RequestBody UpdateMemoryCommand command,
            HttpServletRequest request) {
        return memories.update(id, command, actors.current(request));
    }

    @PostMapping("/{id}/confirm")
    Object confirm(@PathVariable String id, HttpServletRequest request) {
        return memories.confirm(id, actors.current(request));
    }

    @PostMapping("/{id}/reject")
    Object reject(@PathVariable String id, HttpServletRequest request) {
        return memories.reject(id, actors.current(request));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        memories.delete(id, actors.current(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/clear")
    Object clear(
            @Valid @RequestBody(required = false) ClearMemoryCommand command,
            HttpServletRequest request) {
        return java.util.Map.of("deleted", memories.clear(
                command == null ? new ClearMemoryCommand(null) : command,
                actors.current(request)));
    }
}
