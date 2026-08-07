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
        "spring.datasource.url=jdbc:h2:mem:memory-controller;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
@AutoConfigureMockMvc
class MemoryControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void managesMemoryWithRevisionAndTenantScopedEndpoints() throws Exception {
        ObjectNode create = objectMapper.createObjectNode()
                .put("agentId", "default-assistant")
                .put("type", "PROFILE")
                .put("content", "用户希望回答简洁。")
                .put("importance", 0.7);
        String response = mvc.perform(post("/api/v2/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.revision").value(0))
                .andReturn().getResponse().getContentAsString();
        var created = objectMapper.readTree(response);
        String id = created.path("id").asText();

        mvc.perform(get("/api/v2/memories")
                        .param("agentId", "default-assistant")
                        .param("query", "简洁"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        ObjectNode update = objectMapper.createObjectNode()
                .put("type", "PROCEDURAL")
                .put("content", "回答时保持简洁。")
                .put("importance", 0.9)
                .put("expectedRevision", 0);
        mvc.perform(patch("/api/v2/memories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(patch("/api/v2/memories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMORY_REVISION_CONFLICT"));
        mvc.perform(delete("/api/v2/memories/{id}", id))
                .andExpect(status().isNoContent());
    }
}
