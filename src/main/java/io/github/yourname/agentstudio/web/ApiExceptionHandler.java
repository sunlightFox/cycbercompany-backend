package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.node.NodeToolApprovalConflictException;
import io.github.yourname.agentstudio.skill.SkillCompatibilityException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(SkillCompatibilityException.class)
    ProblemDetail skillCompatibility(SkillCompatibilityException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        detail.setProperty("code", "SKILL_INCOMPATIBLE");
        detail.setProperty("report", ex.report());
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

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        // 未预期异常统一返回 500；生产环境还应记录带关联 ID 的服务端日志。
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        detail.setProperty("code", "INTERNAL_ERROR");
        return detail;
    }
}
