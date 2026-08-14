package io.github.yourname.cycbercompany.web;

import io.github.yourname.cycbercompany.mod.ModManifestView;
import io.github.yourname.cycbercompany.mod.ModRegistryService;
import io.github.yourname.cycbercompany.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mods")
class ModMarketplaceController {
    private final CurrentActorProvider actors;
    private final ModRegistryService registry;

    ModMarketplaceController(CurrentActorProvider actors, ModRegistryService registry) {
        this.actors = actors;
        this.registry = registry;
    }

    @GetMapping
    List<ModManifestView> list(HttpServletRequest request) {
        return registry.list(actors.current(request));
    }

    @GetMapping("/{id}")
    ModManifestView get(@PathVariable String id, HttpServletRequest request) {
        return registry.get(id, actors.current(request));
    }

    @PostMapping("/{id}/install")
    @ResponseStatus(HttpStatus.CREATED)
    ModManifestView install(@PathVariable String id, HttpServletRequest request) {
        return registry.install(id, actors.current(request));
    }
}
