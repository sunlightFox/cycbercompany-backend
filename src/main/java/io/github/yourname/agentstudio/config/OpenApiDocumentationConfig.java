package io.github.yourname.agentstudio.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.Operation;
import java.util.List;
import java.util.Locale;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI 文档配置。
 *
 * <p>springdoc 会在应用启动时扫描所有 Spring MVC Controller，自动生成
 * {@code /v3/api-docs} 和 Swagger UI。这个类只补充项目级说明、Bearer Token
 * 认证提示，以及按 URL 前缀自动分组的中文标签。这样新手打开页面时看到的是
 * “会话 / Run / 节点 / 知识库”等业务入口，而不是一长串没有上下文的方法名。
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "CycberCompany API",
                version = "0.0.1",
                description = """
                        CycberCompany 后端接口文档。

                        学习顺序建议：
                        1. 先看 conversations 创建会话；
                        2. 再看 runs 创建一次 Agent 执行；
                        3. 通过 /runs/{id}/events 观察 SSE 事件；
                        4. 最后再读 models、tools、knowledge、skills、mcp、nodes 等能力接口。

                        本地 LOCAL 模式默认不需要 Token；远程 TOKEN 模式使用 Authorization: Bearer <token>。
                        """,
                contact = @Contact(name = "CycberCompany"),
                license = @License(name = "Project local documentation")),
        servers = {
                @Server(url = "http://127.0.0.1:8080", description = "默认本地开发地址"),
                @Server(url = "http://127.0.0.1:8083", description = "personal-local 脚本常用地址")
        },
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "Personal API Token")
class OpenApiDocumentationConfig {

    @Bean
    OpenApiCustomizer chineseBusinessTags() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                String tag = tagFor(path);
                operation.setTags(List.of(tag));
                if (isBlank(operation.getSummary())) {
                    operation.setSummary(summaryFor(path, operation));
                }
                if (isBlank(operation.getDescription())) {
                    operation.setDescription(descriptionFor(path, tag));
                }
            }));
        };
    }

    private static String tagFor(String path) {
        if (path.contains("/conversations")) return "01 会话与消息";
        if (path.contains("/runs")) return "02 Run 编排与事件";
        if (path.contains("/models")) return "03 模型配置";
        if (path.contains("/agents")) return "04 Agent 定义";
        if (path.contains("/tools") || path.contains("/tool-approvals")) return "05 工具与审批";
        if (path.contains("/knowledge")) return "06 知识库 RAG";
        if (path.contains("/skills") || path.contains("/skill-")) return "07 Skill";
        if (path.contains("/mcp-")) return "08 MCP";
        if (path.contains("/nodes") || path.contains("/node-")) return "09 节点执行器";
        if (path.contains("/artifacts")) return "10 Artifact 文件";
        if (path.contains("/web-search")) return "11 Web Search";
        return "99 其他接口";
    }

    private static String summaryFor(String path, Operation operation) {
        return switch (httpVerbFromOperation(operation).toUpperCase(Locale.ROOT) + " " + path) {
            case "POST /api/v1/conversations" -> "创建一个会话";
            case "POST /api/v1/runs" -> "创建一次 Agent Run 并入队";
            case "GET /api/v1/runs/{id}/events" -> "订阅 Run 的 SSE 事件流";
            case "POST /api/v1/nodes/register" -> "节点使用一次性令牌注册";
            case "POST /api/v1/knowledge-search" -> "在可见知识库中检索证据";
            default -> {
                String operationId = operation.getOperationId();
                yield isBlank(operationId) ? path : operationId;
            }
        };
    }

    private static String descriptionFor(String path, String tag) {
        if (path.endsWith("/events")) {
            return "SSE 长连接接口。客户端可携带 Last-Event-ID 补发断线期间遗漏的持久化事件。";
        }
        if (path.contains("/approval")) {
            return "审批接口会绑定当前 Actor、Run、工具调用和参数摘要，避免重新提交被篡改的参数。";
        }
        if (path.contains("/nodes") || path.contains("/node-")) {
            return "节点接口涉及真实机器能力。注意：workspace 是应用层路径限制，不是操作系统沙箱。";
        }
        return "属于“" + tag + "”分组。建议结合 docs/api-and-call-chain-guide.md 的链路图阅读对应 Service。";
    }

    private static String httpVerbFromOperation(Operation operation) {
        String operationId = operation.getOperationId();
        if (operationId == null) {
            return "";
        }
        String lower = operationId.toLowerCase(Locale.ROOT);
        if (lower.contains("delete")) return "DELETE";
        if (lower.contains("update") || lower.contains("patch") || lower.contains("set")) return "PATCH";
        if (lower.contains("create") || lower.contains("post") || lower.contains("call")
                || lower.contains("upload") || lower.contains("install") || lower.contains("rebuild")
                || lower.contains("cancel") || lower.contains("retry") || lower.contains("enable")
                || lower.contains("disable") || lower.contains("decide")) {
            return "POST";
        }
        return "GET";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
