package com.hs.api.advice;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.hs.common.dto.ApiResponse;
import com.hs.common.constant.base.ErrorCode;
import com.hs.user.advice.base.AppException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ControllerAdvice
public class GlobalException {

    @ExceptionHandler(value = Exception.class)
    ResponseEntity<ApiResponse<Void>> handlingRuntimeException(Exception exception) {
        log.error("Exception: ", exception);
        ErrorCode errorCode = ErrorCode.UNCATEGORIZED_EXCEPTION;

        return ResponseEntity.status(errorCode.getStatusCode()).body(buildErrorResponse(errorCode));
    }

    @ExceptionHandler(value = com.hs.common.advice.base.AppException.class)
    ResponseEntity<ApiResponse<Void>> handlingCommonAppException(
            com.hs.common.advice.base.AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();

        return ResponseEntity.status(errorCode.getStatusCode()).body(buildErrorResponse(errorCode));
    }

    @ExceptionHandler(value = AppException.class)
    ResponseEntity<ApiResponse<Void>> handlingUserAppException(AppException exception) {
        com.hs.user.constant.base.ErrorCode errorCode = exception.getErrorCode();

        return ResponseEntity.status(errorCode.getStatusCode())
                .body(buildErrorResponse(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(value = NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handlingNoResourceFoundException(NoResourceFoundException exception) {
        ErrorCode errorCode = ErrorCode.ROUTE_NOT_FOUND;

        return ResponseEntity.status(errorCode.getStatusCode()).body(buildErrorResponse(errorCode));
    }

    @ExceptionHandler(value = AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handlingAccessDeniedException(AccessDeniedException exception) {
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;

        return ResponseEntity.status(errorCode.getStatusCode())
                .body(buildErrorResponse(errorCode));
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handlingValidation(MethodArgumentNotValidException exception) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        var fieldError = exception.getFieldError();
        String message = fieldError != null && fieldError.getDefaultMessage() != null
                ? fieldError.getDefaultMessage()
                : errorCode.getMessage();

        return ResponseEntity.status(errorCode.getStatusCode())
                .body(buildErrorResponse(errorCode, message));
    }

    private ApiResponse<Void> buildErrorResponse(ErrorCode errorCode) {
        return buildErrorResponse(errorCode.getCode(), errorCode.getMessage());
    }

    private ApiResponse<Void> buildErrorResponse(ErrorCode errorCode, String message) {
        return buildErrorResponse(errorCode.getCode(), message);
    }

    private ApiResponse<Void> buildErrorResponse(int code, String message) {
        return ApiResponse.<Void>builder()
                .code(code)
                .message(message)
                .build();
    }
}


