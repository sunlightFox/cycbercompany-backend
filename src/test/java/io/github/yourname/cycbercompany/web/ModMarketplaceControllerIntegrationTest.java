package io.github.yourname.cycbercompany.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mod-marketplace;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
@AutoConfigureMockMvc
class ModMarketplaceControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void installsVideoModBeforeAllowingItsSurfaceToOpen() throws Exception {
        mvc.perform(get("/api/v1/mods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("video-player"))
                .andExpect(jsonPath("$[0].installed").value(false));

        String session = "{\"modId\":\"video-player\",\"surfaceId\":\"player\",\"presentation\":\"docked\"}";
        mvc.perform(post("/api/v1/mod-sessions").contentType(MediaType.APPLICATION_JSON).content(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOD_INSTALLATION_REQUIRED"));

        mvc.perform(post("/api/v1/mods/video-player/install"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.installed").value(true));

        mvc.perform(post("/api/v1/mod-sessions").contentType(MediaType.APPLICATION_JSON).content(session))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modId").value("video-player"))
                .andExpect(jsonPath("$.surfaces[0].surfaceId").value("player"));
    }
}
