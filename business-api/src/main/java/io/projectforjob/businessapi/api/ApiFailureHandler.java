package io.projectforjob.businessapi.api;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiFailureHandler {

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiResponse.ErrorResponse> failure(ApiFailure failure) {
        return ResponseEntity.status(failure.status)
                .body(ApiResponse.ErrorResponse.of(failure.code, failure.getMessage(), failure.details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse.ErrorResponse> invalid() {
        return failure(ApiFailure.invalid());
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiResponse.ErrorResponse> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.ErrorResponse.of("WRITE_BUSY", "写入繁忙，请用原请求标识重试"));
    }
}
