package com.hs.api.advice.handler;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.listing.advice.ListingErrorCode;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.hs.storage.advice.entity.enums.StorageErrorCode;
import com.hs.user.advice.entity.enums.UserErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ControllerAdvice
public class GlobalException {
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handlingRuntimeException(Exception exception) {
        log.error("Exception: ", exception);
        return buildResponse(ErrorCode.UNCATEGORIZED_EXCEPTION);
    }

    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiResponse<Void>> handlingAppException(AppException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(buildErrorResponse(exception.getCode(), exception.getErrorMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handlingNoResourceFoundException(NoResourceFoundException exception) {
        return buildResponse(ErrorCode.ROUTE_NOT_FOUND);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handlingAccessDeniedException(AccessDeniedException exception) {
        return buildResponse(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Object> handlingValidation(MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(
                        error.getField(), "INVALID", error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(java.util.Map.of("code", "VALIDATION_ERROR", "errors", errors));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<Void>> handlingOptimisticLock(ObjectOptimisticLockingFailureException exception) {
        return buildResponse(ListingErrorCode.LISTING_VERSION_CONFLICT);
    }

    private AppException.ErrorCode resolveValidationError(String key) {
        try {
            return UserErrorCode.valueOf(key);
        } catch (IllegalArgumentException ignored) {
            // Not a user-service validation key.
        }

        try {
            return StorageErrorCode.valueOf(key);
        } catch (IllegalArgumentException ignored) {
            // The validation message is plain text.
        }

        try {
            return ContractErrorCode.valueOf(key);
        } catch (IllegalArgumentException ignored) {
            // Not a contract validation key.
        }

        return null;
    }

    private ResponseEntity<ApiResponse<Void>> buildResponse(AppException.ErrorCode errorCode) {
        return buildResponse(errorCode, errorCode.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> buildResponse(AppException.ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getStatusCode())
                .body(buildErrorResponse(errorCode.getCode(), message));
    }

    private ApiResponse<Void> buildErrorResponse(int code, String message) {
        return ApiResponse.<Void>builder().code(code).message(message).build();
    }

    private record FieldError(String field, String code, String message) {
    }
}
