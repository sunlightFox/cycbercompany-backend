package io.github.yourname.agentstudio.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AgentStudioControllerTest {

    @Test
    void allowsLocalExecutorBootstrapFromLoopbackOrExplicitLocalProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setRemoteAddr("127.0.0.1");
        assertThat(AgentStudioController.canBootstrapLocalExecutor(request, false)).isTrue();

        request.setRemoteAddr("10.0.0.18");
        assertThat(AgentStudioController.canBootstrapLocalExecutor(request, false)).isFalse();
        assertThat(AgentStudioController.canBootstrapLocalExecutor(request, true)).isTrue();
    }
}
