package io.github.yourname.agentstudio.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class SkillMarketplacePageController {

    @GetMapping({"/skill-marketplace", "/skills/marketplace"})
    String page() {
        return "forward:/skill-marketplace.html";
    }
}
