package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.model.ModelGateway;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionIntentRouterTest {

    @Test
    void routesARealBuildAndRunRequestToTheAuthorizedLocalPosture() {
        ExecutionIntentRouter router = new ExecutionIntentRouter(
                response("{\"intent\":\"LOCAL_EXECUTION\",\"confidence\":0.94,\"reason\":\"The user asks to create and run software.\"}"),
                new ObjectMapper());

        ExecutionIntentDecision result = router.decide("写一个贪吃蛇游戏，启动并验证页面", "model-1");

        assertThat(result.intent()).isEqualTo(ExecutionIntent.LOCAL_EXECUTION);
        assertThat(result.source()).isEqualTo("model");
    }

    @Test
    void preservesAPureExplanationAsChatWhenTheModelClassifiesItAsChat() {
        ExecutionIntentRouter router = new ExecutionIntentRouter(
                response("{\"intent\":\"CHAT\",\"confidence\":0.98,\"reason\":\"The user asks for an explanation.\"}"),
                new ObjectMapper());

        ExecutionIntentDecision result = router.decide("解释一下贪吃蛇游戏的碰撞检测原理", "model-1");

        assertThat(result.intent()).isEqualTo(ExecutionIntent.CHAT);
        assertThat(result.source()).isEqualTo("model");
    }

    @Test
    void turnsAmbiguousOrMalformedActionClassificationIntoClarification() {
        ExecutionIntentRouter malformed = new ExecutionIntentRouter(response("not json"), new ObjectMapper());
        ExecutionIntentRouter uncertain = new ExecutionIntentRouter(
                response("{\"intent\":\"CHAT\",\"confidence\":0.42,\"reason\":\"uncertain\"}"), new ObjectMapper());

        assertThat(malformed.decide("部署这个应用", "model-1").intent()).isEqualTo(ExecutionIntent.CLARIFY);
        assertThat(uncertain.decide("帮我创建一个网站", "model-1").intent()).isEqualTo(ExecutionIntent.CLARIFY);
    }

    @Test
    void usesConservativeFallbackOnlyWhenTheRouterModelIsUnavailable() {
        ExecutionIntentRouter router = new ExecutionIntentRouter(request -> {
            throw new IllegalStateException("provider unavailable");
        }, new ObjectMapper());

        assertThat(router.decide("解释一下贪吃蛇的算法", "model-1").intent()).isEqualTo(ExecutionIntent.CHAT);
        assertThat(router.decide("帮我部署贪吃蛇", "model-1").intent()).isEqualTo(ExecutionIntent.CLARIFY);
    }

    private static ModelGateway response(String content) {
        return request -> new ModelGateway.ModelAnswer(content, 1, 1, "test", List.of(), "stop");
    }
}
