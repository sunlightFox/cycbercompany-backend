package io.github.yourname.agentstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-persona-controller;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
@AutoConfigureMockMvc
class UserPersonaControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void managesDefaultsAttributesAndRevisionConflicts() throws Exception {
        ObjectNode developer = objectMapper.createObjectNode()
                .put("name", "Developer")
                .put("description", "Software development context");
        developer.putObject("attributes")
                .put("language", "zh-CN")
                .put("experienceLevel", "senior");
        var first = objectMapper.readTree(mvc.perform(post("/api/v2/personas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(developer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultPersona").value(true))
                .andExpect(jsonPath("$.attributes.language").value("zh-CN"))
                .andReturn().getResponse().getContentAsString());

        ObjectNode writer = objectMapper.createObjectNode()
                .put("name", "Writer")
                .put("description", "Writing context");
        writer.putObject("attributes").put("tone", "concise");
        var second = objectMapper.readTree(mvc.perform(post("/api/v2/personas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(writer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultPersona").value(false))
                .andReturn().getResponse().getContentAsString());
        String firstId = first.path("id").asText();
        String secondId = second.path("id").asText();

        ObjectNode conversation = objectMapper.createObjectNode()
                .put("title", "Persona scoped chat")
                .put("personaId", firstId);
        var createdConversation = objectMapper.readTree(mvc.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(conversation)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personaId").value(firstId))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/api/v1/conversations/{id}/persona", createdConversation.path("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("personaId", secondId).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personaId").value(secondId));

        mvc.perform(post("/api/v2/personas/{id}/default", secondId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPersona").value(true));
        var firstAfterDefaultSwitch = objectMapper.readTree(mvc.perform(get("/api/v2/personas/{id}", firstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPersona").value(false))
                .andReturn().getResponse().getContentAsString());
        long firstRevision = firstAfterDefaultSwitch.path("revision").asLong();
        ObjectNode update = objectMapper.createObjectNode()
                .put("name", "Developer")
                .put("description", "Updated development context")
                .put("expectedRevision", firstRevision);
        update.putObject("attributes").put("language", "zh-CN");
        mvc.perform(patch("/api/v2/personas/{id}", firstId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(firstRevision + 1));
        mvc.perform(patch("/api/v2/personas/{id}", firstId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_PERSONA_REVISION_CONFLICT"));

        mvc.perform(delete("/api/v2/personas/{id}", secondId))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v2/personas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(firstId))
                .andExpect(jsonPath("$[0].defaultPersona").value(true));
    }
}
