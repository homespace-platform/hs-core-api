package com.hs.api.controller.user;

import com.hs.common.dto.ApiResponse;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.location.VietmapService;
import com.hs.user.utils.CurrentUserUtils;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Reusable address lookup API; does not save User, Listing or Branch addresses. */
@RestController @RequestMapping("/locations") @RequiredArgsConstructor
public class VietmapController {
    private final VietmapService vietmap;
    private final CurrentUserUtils users;
    private void requireUser() {
        var context=UserContextHolder.get();
        if(context==null || context.userId()==null || context.userId().isBlank())
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        users.getCurrentUser();
    }
    @GetMapping("/autocomplete") public ApiResponse<List<VietmapService.Suggestion>> autocomplete(@RequestParam("text") String text) {
        requireUser();return ApiResponse.<List<VietmapService.Suggestion>>builder().result(vietmap.autocomplete(text)).build();
    }
    @GetMapping("/geocode") public ApiResponse<List<VietmapService.Suggestion>> geocode(@RequestParam("text") String text) {
        requireUser();return ApiResponse.<List<VietmapService.Suggestion>>builder().result(vietmap.geocode(text)).build();
    }
    @GetMapping("/place") public ApiResponse<VietmapService.Place> place(@RequestParam("refId") String refId) {
        requireUser();return ApiResponse.<VietmapService.Place>builder().result(vietmap.place(refId)).build();
    }
    @GetMapping("/reverse") public ApiResponse<List<VietmapService.Suggestion>> reverse(@RequestParam("latitude") BigDecimal latitude,@RequestParam("longitude") BigDecimal longitude) {
        requireUser();return ApiResponse.<List<VietmapService.Suggestion>>builder().result(vietmap.reverse(latitude,longitude)).build();
    }
    // Keep query binding failures in this API's 400 envelope, rather than the global generic 500 handler.
    @ExceptionHandler({MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalidQuery() {
        return ResponseEntity.badRequest().body(ApiResponse.<Void>builder().code(ErrorCode.INVALID_REQUEST.getCode())
                .message("Tham số tìm địa chỉ thiếu hoặc không đúng định dạng.").build());
    }
}
