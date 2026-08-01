package io.github.yourname.agentstudio;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 模块边界回归测试。
 *
 * <p>Spring Modulith 会从各模块的 {@code package-info.java} 读取允许依赖。新增功能如果绕过
 * ToolRouter 或形成循环依赖，这个测试会在普通单元测试阶段直接给出模块和类型级错误。
 */
class AgentStudioModularityTest {

    @Test
    void applicationModulesRespectDeclaredDependencies() {
        // config 负责 Spring Boot 属性绑定和启动装配，不是业务模块。把它排除后，验证聚焦于
        // Agent、Skill、Tool、Node、MCP 与编排层的领域依赖方向。
        ApplicationModules.of(
                AgentstudioApplication.class,
                resideOutsideOfPackage("io.github.yourname.agentstudio.config.."))
                .verify();
    }
}
