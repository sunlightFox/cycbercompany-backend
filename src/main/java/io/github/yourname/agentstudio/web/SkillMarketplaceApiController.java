package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.skill.SkillMarketplaceService;
import io.github.yourname.agentstudio.skill.SkillMarketplaceView;
import io.github.yourname.agentstudio.skill.InstallSkillHubSkillCommand;
import io.github.yourname.agentstudio.skill.ClawHubSkillService;
import io.github.yourname.agentstudio.skill.SkillCatalog;
import io.github.yourname.agentstudio.skill.SkillHubSkillService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SkillMarketplaceApiController {

    private final SkillMarketplaceService marketplace;
    private final SkillCatalog skills;
    private final SkillHubSkillService skillHub;
    private final ObjectMapper objectMapper;

    SkillMarketplaceApiController(SkillMarketplaceService marketplace, SkillCatalog skills, SkillHubSkillService skillHub, ObjectMapper objectMapper) {
        this.marketplace = marketplace;
        this.skills = skills;
        this.skillHub = skillHub;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/skill-marketplace")
    SkillMarketplaceView overview(@RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit) {
        return marketplace.overview(query, limit);
    }

    @PostMapping("/skills/install/skillhub")
    @ResponseStatus(HttpStatus.CREATED)
    Object installSkillHub(@Valid @org.springframework.web.bind.annotation.RequestBody InstallSkillHubSkillCommand command) {
        ClawHubSkillService adapter = new ClawHubSkillService(objectMapper) {
            @Override
            public ClawHubSkillService.ClawHubInstall download(String reference) { return skillHub.download(reference); }
        };
        return skills.installClawHub(new io.github.yourname.agentstudio.skill.InstallClawHubSkillCommand(
                command.reference(), command.id(), command.enabled(), command.overwrite()), adapter);
    }
}
