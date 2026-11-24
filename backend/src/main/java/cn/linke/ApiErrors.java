package cn.linke;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> known(ApiError e) {
        var response=ResponseEntity.status(e.status);
        if (e.status==429) response.header("Retry-After","900");
        return response.body(Map.of("code", e.code, "message", e.getMessage(), "fieldErrors", e.fields));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> uploadTooLarge(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().endsWith("/verifications")) return known(VerificationProof.tooLarge());
        return known(request.getRequestURI().endsWith("/upload/resume") ? ResumeService.tooLarge() : AvatarImage.tooLarge());
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missingResource() { return known(new ApiError(404, "NOT_FOUND", "资源不存在")); }
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
