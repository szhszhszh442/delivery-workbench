package io.projectforjob.businessapi.api;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiResponse<T>(T data, String requestId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, UUID.randomUUID().toString());
    }

    public record ErrorResponse(ErrorBody error, String requestId) {
        public static ErrorResponse of(String code, String message) {
            return of(code, message, null);
        }

        static ErrorResponse of(String code, String message, Map<String, ?> details) {
            return new ErrorResponse(new ErrorBody(code, message, details), UUID.randomUUID().toString());
        }
    }

    public record ErrorBody(String code, String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, ?> details) {
    }

    public record Replay<T>(T data, String requestId, boolean replayed) {
    }
}
