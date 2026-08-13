package io.github.yourname.agentstudio.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * 面向 STDIO MCP Server 的最小 JSON-RPC 客户端。
 *
 * <p>MCP 的 STDIO 传输以“一行一个 JSON-RPC 消息”的方式在子进程标准输入/输出间通信。
 * 这里采取“一次操作启动一个进程”的保守策略：虽然比复用连接慢，但每次调用都能设置时限、
 * 清理子进程，更适合本地学习项目理解生命周期与安全边界。
 */
@Service
class McpStdioClient {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong(1);

    McpStdioClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<UpsertMcpToolCommand> listTools(McpConnectionService.McpRuntimeConnection connection) {
        // 先发 tools/list，再把远端描述转换为本地可编辑的工具元数据。
        return withSession(connection, session -> {
            JsonNode result = session.request("tools/list", Map.of(), STARTUP_TIMEOUT);
            List<UpsertMcpToolCommand> tools = new ArrayList<>();
            for (JsonNode tool : result.path("tools")) {
                String name = tool.path("name").asText();
                if (name == null || name.isBlank()) {
                    continue;
                }
                String schema = tool.has("inputSchema") ? tool.path("inputSchema").toString() : "{}";
                tools.add(new UpsertMcpToolCommand(
                        name,
                        tool.path("description").asText(""),
                        schema,
                        inferRisk(name, tool.path("description").asText("")),
                        false,
                        true));
            }
            return tools;
        });
    }

    McpToolCallResult callTool(
            McpConnectionService.McpRuntimeConnection connection,
            String toolName,
            Map<String, Object> arguments) {
        return withSession(connection, session -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? Map.of() : arguments);
            JsonNode result = session.request("tools/call", params, CALL_TIMEOUT);
            // MCP 的业务失败通常写在 result.isError，而非 JSON-RPC 的 error 字段。
            boolean isError = result.path("isError").asBoolean(false);
            List<Map<String, Object>> content = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonNode item : result.path("content")) {
                Map<String, Object> mapped = objectMapper.convertValue(item, MAP_TYPE);
                content.add(mapped);
                if ("text".equals(item.path("type").asText()) && item.has("text")) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append(item.path("text").asText());
                }
            }
            return new McpToolCallResult(
                    connection.id(),
                    toolName,
                    isError,
                    text.toString(),
                    content,
                    objectMapper.convertValue(result, Object.class));
        });
    }

    private <T> T withSession(McpConnectionService.McpRuntimeConnection connection, SessionOperation<T> operation) {
        if (connection.transportType() != McpTransportType.STDIO) {
            throw new IllegalArgumentException("Only STDIO MCP execution is implemented in this backend version.");
        }
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(connection.command());
            command.addAll(connection.args());
            // ProcessBuilder 接受分词后的列表，避免经 shell 拼接命令而引入转义和注入问题。
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(connection.env());
            builder.redirectErrorStream(false);
            process = builder.start();
            drainStderr(process, connection.id());
            try (Session session = new Session(process)) {
                initialize(session);
                return operation.run(session);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("MCP STDIO process failed: " + ex.getMessage(), ex);
        } finally {
            stopProcess(process);
        }
    }

    private void initialize(Session session) {
        // MCP 协议要求先 initialize 成功，再发送 initialized 通知，才能调用 tools/* 方法。
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "cycbercompany", "version", "0.0.1"));
        session.request("initialize", params, STARTUP_TIMEOUT);
        session.notify("notifications/initialized", Map.of());
    }

    private void drainStderr(Process process, String connectionId) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // 只排空不解析。大量 stderr 日志若无人读取会填满管道并卡住子进程。
                }
            } catch (IOException ignored) {
                // Process is usually being destroyed when this happens.
            }
        });
    }

    private void stopProcess(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            // 先给进程一个正常退出窗口，超时后才强制终止，降低留下孤儿进程的概率。
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static RiskLevel inferRisk(String name, String description) {
        String normalized = (name + " " + description).toLowerCase();
        if (normalized.contains("delete")
                || normalized.contains("write")
                || normalized.contains("update")
                || normalized.contains("create")
                || normalized.contains("execute")
                || normalized.contains("run")) {
            return RiskLevel.HIGH;
        }
        if (normalized.contains("fetch")
                || normalized.contains("browser")
                || normalized.contains("file")
                || normalized.contains("database")) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    @FunctionalInterface
    private interface SessionOperation<T> {
        T run(Session session);
    }

    private class Session implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;

        Session(Process process) {
            this.process = process;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        JsonNode request(String method, Map<String, Object> params, Duration timeout) {
            // id 将请求与响应配对；即使服务端期间穿插通知，也只接收自己的响应。
            long id = ids.getAndIncrement();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("method", method);
            payload.put("params", params == null ? Map.of() : params);
            write(payload);

            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String line = readLine(deadline);
                if (line == null || line.isBlank()) {
                    continue;
                }
                JsonNode message = parse(line);
                if (message.has("id") && message.path("id").asLong() == id) {
                    if (message.has("error")) {
                        throw new IllegalStateException("MCP error from " + method + ": " + message.path("error"));
                    }
                    return message.path("result");
                }
            }
            throw new IllegalStateException("MCP request timed out: " + method);
        }

        void notify(String method, Map<String, Object> params) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            payload.put("params", params == null ? Map.of() : params);
            write(payload);
        }

        private void write(Map<String, Object> payload) {
            try {
                writer.write(objectMapper.writeValueAsString(payload));
                writer.newLine();
                writer.flush();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to write MCP request: " + ex.getMessage(), ex);
            }
        }

        private String readLine(long deadlineNanos) {
            try {
                while (System.nanoTime() < deadlineNanos) {
                    if (reader.ready()) {
                        return reader.readLine();
                    }
                    if (!process.isAlive()) {
                        // 子进程提前退出比“等待到超时”更有诊断价值，立即反馈给调用方。
                        throw new IllegalStateException("MCP process exited before sending a response.");
                    }
                    Thread.sleep(25);
                }
                return null;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read MCP response: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while reading MCP response", ex);
            }
        }

        private JsonNode parse(String line) {
            try {
                return objectMapper.readTree(line);
            } catch (IOException ex) {
                throw new IllegalStateException("Invalid MCP JSON response: " + line, ex);
            }
        }

        @Override
        public void close() {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
    }
}
