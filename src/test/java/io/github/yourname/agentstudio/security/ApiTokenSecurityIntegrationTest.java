package io.github.yourname.agentstudio.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.security.mode=TOKEN",
        "app.security.api-token=0123456789abcdef0123456789abcdef",
        "app.security.tenant-id=integration-tenant",
        "app.security.user-id=integration-user",
        "server.address=127.0.0.1"
})
@AutoConfigureMockMvc
class ApiTokenSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void protectedApiRejectsMissingOrInvalidTokenAndAcceptsConfiguredToken() throws Exception {
        mvc.perform(get("/api/v1/nodes"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/nodes").header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/nodes").header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer 0123456789abcdef0123456789abcdef"))
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
    void localExecutorBootstrapRequiresApiTokenInTokenMode() throws Exception {
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
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/local-executor/bootstrap")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer 0123456789abcdef0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
    }
}
