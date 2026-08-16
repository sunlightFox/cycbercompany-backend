package io.github.yourname.cycbercompany.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.security.tenant-id=integration-tenant",
        "app.security.user-id=integration-user",
        "server.address=127.0.0.1"
})
@AutoConfigureMockMvc
class ApiTokenSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void apiDoesNotRequireTheRemovedApiToken() throws Exception {
        mvc.perform(get("/api/v1/nodes"))
                .andExpect(status().isOk());
    }

    @Test
    void healthEndpointRemainsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk());
    }

    @Test
    void localExecutorBootstrapDoesNotRequireApiToken() throws Exception {
        String payload = """
                {
                  "name": "This computer",
                  "hostname": "test-host",
                  "osName": "Windows",
                  "osArch": "amd64",
                  "clientVersion": "test"
                }
                """;

        mvc.perform(post("/api/v1/local-executor/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
    }
}
