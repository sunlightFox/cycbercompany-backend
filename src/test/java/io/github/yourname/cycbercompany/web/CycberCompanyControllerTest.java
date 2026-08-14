package io.github.yourname.cycbercompany.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CycberCompanyControllerTest {

    @Test
    void allowsLocalExecutorBootstrapFromLoopbackOrExplicitLocalProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setRemoteAddr("127.0.0.1");
        assertThat(CycberCompanyController.canBootstrapLocalExecutor(request, false)).isTrue();

        request.setRemoteAddr("10.0.0.18");
        assertThat(CycberCompanyController.canBootstrapLocalExecutor(request, false)).isFalse();
        assertThat(CycberCompanyController.canBootstrapLocalExecutor(request, true)).isTrue();
    }
}
