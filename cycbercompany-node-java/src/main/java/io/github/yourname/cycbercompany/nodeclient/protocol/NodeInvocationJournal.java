package io.github.yourname.cycbercompany.nodeclient.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点本地调用 Journal。
 *
 * <p>这里采用“每个 invocation 一个原子 JSON 文件”，而不是把执行事实只留在内存中。小型个人节点
 * 不需要引入 SQLite 依赖也能获得关键语义：先落盘、去重、重启恢复和状态对账。
 */
public final class NodeInvocationJournal {

    private static final TypeReference<Map<String, NodeJournalEntry>> ENTRIES = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path journalFile;
    private final Map<String, NodeJournalEntry> entries = new LinkedHashMap<>();

    public NodeInvocationJournal(ObjectMapper objectMapper, Path dataRoot) {
        // Journal 是节点可靠性边界，不能依赖调用方“碰巧”给 ObjectMapper 注册了 Java 时间模块。
        // 注册操作幂等，生产入口已注册时也不会改变既有行为。
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        try {
            Files.createDirectories(dataRoot);
            this.journalFile = dataRoot.resolve("invocation-journal.json");
            loadAndRecover();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize the node invocation journal.", ex);
        }
    }

    /**
     * 原子记录 ACCEPTED；同 ID 只能匹配同一工具、摘要和 attempt。不同内容的重复 ID 一律拒绝。
     */
    public synchronized Acceptance accept(
            String invocationId,
            String toolName,
            String argumentsDigest,
            int attempt) {
        require(invocationId, "invocationId");
        require(toolName, "toolName");
        require(argumentsDigest, "argumentsDigest");
        NodeJournalEntry existing = entries.get(invocationId);
        if (existing != null) {
            if (!existing.toolName().equals(toolName)
                    || !existing.argumentsDigest().equals(argumentsDigest)
                    || existing.attempt() != Math.max(1, attempt)) {
                return new Acceptance(Decision.CONFLICT, existing);
            }
            return new Acceptance(existing.terminal() ? Decision.CACHED_TERMINAL : Decision.ALREADY_ACTIVE, existing);
        }
        Instant now = Instant.now();
        NodeJournalEntry accepted = new NodeJournalEntry(
                invocationId, toolName, argumentsDigest, attempt, "ACCEPTED", null, null, now, null, null, now);
        entries.put(invocationId, accepted);
        persist();
        return new Acceptance(Decision.NEW, accepted);
    }

    public synchronized NodeJournalEntry start(String invocationId) {
        NodeJournalEntry current = requireEntry(invocationId);
        if (current.terminal()) {
            return current;
        }
        // 取消帧可能恰好落在“已接受”与工作线程真正开始之间。此时尚未执行本机工具，
        // 因而绝不能把 CANCEL_REQUESTED 覆盖为 RUNNING 后继续产生副作用。调用方会将
        // 该状态收敛为 CANCELLED，并把结果如实回传给服务端。
        if ("CANCEL_REQUESTED".equals(current.status())) {
            return current;
        }
        Instant now = Instant.now();
        NodeJournalEntry started = new NodeJournalEntry(
                current.invocationId(), current.toolName(), current.argumentsDigest(), current.attempt(), "RUNNING",
                current.result(), current.errorMessage(), current.acceptedAt(),
                current.startedAt() == null ? now : current.startedAt(), current.finishedAt(), now);
        entries.put(invocationId, started);
        persist();
        return started;
    }

    public synchronized NodeJournalEntry finish(
            String invocationId,
            String status,
            Map<String, Object> result,
            String errorMessage) {
        NodeJournalEntry current = requireEntry(invocationId);
        if (current.terminal()) {
            return current;
        }
        Instant now = Instant.now();
        NodeJournalEntry finished = new NodeJournalEntry(
                current.invocationId(), current.toolName(), current.argumentsDigest(), current.attempt(),
                terminalStatus(status), result, errorMessage, current.acceptedAt(), current.startedAt(), now, now);
        entries.put(invocationId, finished);
        persist();
        return finished;
    }

    /** 收到取消只表示请求到达；仍等待执行线程的实际终态。 */
    public synchronized NodeJournalEntry cancelRequested(String invocationId) {
        NodeJournalEntry current = requireEntry(invocationId);
        if (current.terminal()) {
            return current;
        }
        Instant now = Instant.now();
        NodeJournalEntry requested = new NodeJournalEntry(
                current.invocationId(), current.toolName(), current.argumentsDigest(), current.attempt(), "CANCEL_REQUESTED",
                current.result(), current.errorMessage(), current.acceptedAt(), current.startedAt(), null, now);
        entries.put(invocationId, requested);
        persist();
        return requested;
    }

    public synchronized NodeJournalEntry find(String invocationId) {
        return entries.get(invocationId);
    }

    private void loadAndRecover() throws IOException {
        if (Files.isRegularFile(journalFile) && Files.size(journalFile) > 0) {
            entries.putAll(objectMapper.readValue(Files.readAllBytes(journalFile), ENTRIES));
        }
        boolean changed = false;
        Instant now = Instant.now();
        for (Map.Entry<String, NodeJournalEntry> item : new LinkedHashMap<>(entries).entrySet()) {
            NodeJournalEntry entry = item.getValue();
            if (!entry.terminal()) {
                // 进程异常退出时无法得知工具是否完成副作用；诚实标记 UNKNOWN，绝不自动重放。
                entries.put(item.getKey(), new NodeJournalEntry(
                        entry.invocationId(), entry.toolName(), entry.argumentsDigest(), entry.attempt(), "UNKNOWN",
                        entry.result(), "Node restarted before the invocation terminal state was confirmed.",
                        entry.acceptedAt(), entry.startedAt(), now, now));
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    private void persist() {
        try {
            Path temp = journalFile.resolveSibling(journalFile.getFileName() + ".tmp");
            byte[] bytes = objectMapper.writeValueAsBytes(entries);
            Files.write(temp, bytes);
            try {
                Files.move(temp, journalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, journalFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            // 不允许“没有成功落盘也继续执行”。调用方会收到失败，不会开始本机副作用。
            throw new IllegalStateException("Unable to persist the node invocation journal.", ex);
        }
    }

    private NodeJournalEntry requireEntry(String invocationId) {
        NodeJournalEntry entry = entries.get(invocationId);
        if (entry == null) {
            throw new IllegalArgumentException("Invocation is not present in the local journal: " + invocationId);
        }
        return entry;
    }

    private static String terminalStatus(String value) {
        return switch (value == null ? "FAILED" : value) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN" -> value == null ? "FAILED" : value;
            default -> "FAILED";
        };
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for the invocation journal.");
        }
    }

    public enum Decision {
        NEW,
        ALREADY_ACTIVE,
        CACHED_TERMINAL,
        CONFLICT
    }

    public record Acceptance(Decision decision, NodeJournalEntry entry) {
    }
}
