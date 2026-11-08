package cn.linke;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

public class ApiError extends RuntimeException {
    final int status;
    final String code;
    final Map<String, String> fields;
    ApiError(int status, String code, String message) { this(status, code, message, Map.of()); }
    ApiError(int status, String code, String message, Map<String, String> fields) {
        super(message); this.status = status; this.code = code; this.fields = fields;
    }
    static ApiError invalid(String field, String message) {
        return new ApiError(422, "INVALID_INPUT", "请检查填写内容", Map.of(field, message));
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> known(ApiError e) {
        return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage(), "fieldErrors", e.fields));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() { return known(new ApiError(422, "INVALID_INPUT", "请求内容不完整或格式不正确")); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> invalidPath() { return known(new ApiError(404, "NOT_FOUND", "内容不存在或不可查看")); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> methodNotAllowed() { return known(new ApiError(405, "METHOD_NOT_ALLOWED", "此操作需要通过页面明确提交")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() { return known(new ApiError(409, "CONFLICT", "内容已变化或账号/邮箱已被使用，请重新核对")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        // Do not serialize exception messages, SQL, passwords, mail addresses or link tokens.
        return known(new ApiError(503, "SERVICE_UNAVAILABLE", "服务暂时不可用，请稍后重试；输入内容仍保留"));
    }
}
