package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.agent.AgentManifestValidationException;
import io.github.yourname.agentstudio.agent.AgentIdentityRevisionConflictException;
import io.github.yourname.agentstudio.agent.AgentEvaluationRequiredException;
import io.github.yourname.agentstudio.memory.MemoryRevisionConflictException;
import io.github.yourname.agentstudio.persona.UserPersonaRevisionConflictException;
import io.github.yourname.agentstudio.agent.AgentRevisionConflictException;
import io.github.yourname.agentstudio.conversation.ConversationArchivedException;
import io.github.yourname.agentstudio.node.NodeToolApprovalConflictException;
import io.github.yourname.agentstudio.node.LocalComputerControlNotReadyException;
import io.github.yourname.agentstudio.execution.ExecutionModeChangeNotAllowedException;
import io.github.yourname.agentstudio.orchestration.ExecutionIntentClarificationException;
import io.github.yourname.agentstudio.skill.SkillCompatibilityException;
import io.github.yourname.agentstudio.mod.ModInstallationRequiredException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(AgentManifestValidationException.class)
    ProblemDetail agentManifestInvalid(AgentManifestValidationException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        detail.setProperty("code", "AGENT_MANIFEST_INVALID");
        detail.setProperty("errors", ex.errors());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(AgentEvaluationRequiredException.class)
    ProblemDetail agentEvaluationRequired(AgentEvaluationRequiredException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        detail.setProperty("code", "AGENT_EVALUATION_REQUIRED");
        detail.setProperty("problems", ex.problems());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(AgentRevisionConflictException.class)
    ProblemDetail agentRevisionConflict(AgentRevisionConflictException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "AGENT_REVISION_CONFLICT");
        detail.setProperty("versionId", ex.versionId());
        detail.setProperty("expectedRevision", ex.expectedRevision());
        detail.setProperty("actualRevision", ex.actualRevision());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(AgentIdentityRevisionConflictException.class)
    ProblemDetail agentIdentityRevisionConflict(AgentIdentityRevisionConflictException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "AGENT_IDENTITY_REVISION_CONFLICT");
        detail.setProperty("agentId", ex.agentId());
        detail.setProperty("expectedRevision", ex.expectedRevision());
        detail.setProperty("actualRevision", ex.actualRevision());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(MemoryRevisionConflictException.class)
    ProblemDetail memoryRevisionConflict(MemoryRevisionConflictException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "MEMORY_REVISION_CONFLICT");
        detail.setProperty("memoryId", ex.memoryId());
        detail.setProperty("expectedRevision", ex.expectedRevision());
        detail.setProperty("actualRevision", ex.actualRevision());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(UserPersonaRevisionConflictException.class)
    ProblemDetail userPersonaRevisionConflict(UserPersonaRevisionConflictException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "USER_PERSONA_REVISION_CONFLICT");
        detail.setProperty("personaId", ex.personaId());
        detail.setProperty("expectedRevision", ex.expectedRevision());
        detail.setProperty("actualRevision", ex.actualRevision());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(SkillCompatibilityException.class)
    ProblemDetail skillCompatibility(SkillCompatibilityException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        detail.setProperty("code", "SKILL_INCOMPATIBLE");
        detail.setProperty("report", ex.report());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(ModInstallationRequiredException.class)
    ProblemDetail modInstallationRequired(ModInstallationRequiredException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "MOD_INSTALLATION_REQUIRED");
        detail.setProperty("modId", ex.modId());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(NodeToolApprovalConflictException.class)
    ProblemDetail approvalConflict(NodeToolApprovalConflictException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "NODE_TOOL_APPROVAL_ALREADY_DECIDED");
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(ExecutionModeChangeNotAllowedException.class)
    ProblemDetail executionModeConflict(ExecutionModeChangeNotAllowedException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "EXECUTION_MODE_NOT_ALLOWED");
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(ExecutionIntentClarificationException.class)
    ProblemDetail executionIntentClarification(ExecutionIntentClarificationException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        detail.setProperty("code", "EXECUTION_TARGET_CLARIFICATION_REQUIRED");
        detail.setProperty("decision", ex.decision());
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(ConversationArchivedException.class)
    ProblemDetail conversationArchived(ConversationArchivedException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("code", "CONVERSATION_ARCHIVED");
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(LocalComputerControlNotReadyException.class)
    ProblemDetail localComputerControlNotReady(LocalComputerControlNotReadyException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setProperty("code", "LOCAL_COMPUTER_CONTROL_NOT_READY");
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    /** 将业务输入错误转换为 RFC 9457 风格的 ProblemDetail，而不是暴露堆栈。 */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setProperty("code", "BAD_REQUEST");
        detail.setProperty("timestamp", Instant.now().toString());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        detail.setProperty("code", "VALIDATION_FAILED");
        // 把 Bean Validation 的字段错误整理为稳定、便于前端表单展示的结构。
        detail.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage()))
                .toList());
        return detail;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        detail.setProperty("code", "METHOD_NOT_ALLOWED");
        return detail;
    }

    /**
     * A browser may close an SSE stream immediately after receiving its terminal
     * event. The response is already committed at that point, so attempting to
     * serialize a JSON ProblemDetail would create a misleading converter warning.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void asyncRequestNotUsable(AsyncRequestNotUsableException ignored) {
        // The client already closed the stream; there is no response to write.
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        // 未预期异常统一返回 500；生产环境还应记录带关联 ID 的服务端日志。
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        detail.setProperty("code", "INTERNAL_ERROR");
        return detail;
    }
}
