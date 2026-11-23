package cn.linke;

import java.util.Map;

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
