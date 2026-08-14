package io.github.yourname.cycbercompany.node;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeToolInvocationRepository extends JpaRepository<NodeToolInvocationEntity, String> {

    List<NodeToolInvocationEntity> findByTenantIdAndRunIdOrderByCreatedAtAsc(String tenantId, String runId);

    /**
     * 查询某个任务中仍需要与节点 Journal 核对的命令。
     *
     * <p>tenantId 必须参与查询，避免调用方仅凭猜测的 runId 读取或操作其他租户的命令记录。
     * 这里的记录只用于发送 {@code tool.status} 查询，绝不能据此重新发送 {@code tool.invoke}。
     */
    List<NodeToolInvocationEntity> findByTenantIdAndRunIdAndStatusInOrderByCreatedAtAsc(
            String tenantId,
            String runId,
            java.util.Collection<NodeToolInvocationStatus> statuses);

    Optional<NodeToolInvocationEntity> findFirstByTenantIdAndRunIdAndToolCallIdOrderByCreatedAtDesc(
            String tenantId,
            String runId,
            String toolCallId);

    Optional<NodeToolInvocationEntity> findByIdAndNodeId(String id, String nodeId);

    List<NodeToolInvocationEntity> findByNodeIdAndStatusInOrderByCreatedAtAsc(
            String nodeId,
            java.util.Collection<NodeToolInvocationStatus> statuses);

    /** 重连后只对账未完成/未知调用，绝不根据这些记录重新发送 tool.invoke。 */
    List<NodeToolInvocationEntity> findByNodeIdAndStatusInOrderByCreatedAtAsc(
            String nodeId,
            java.util.Collection<NodeToolInvocationStatus> statuses,
            org.springframework.data.domain.Pageable pageable);
}
