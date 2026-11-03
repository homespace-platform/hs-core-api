package com.hs.api.controller.contract;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.service.ComplaintCaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
public class ComplaintController {
    private final ComplaintCaseService service;
    @GetMapping("/rental-requests/{id}/complaints") public ApiResponse<List<ComplaintView>> list(@PathVariable String id){return ApiResponse.<List<ComplaintView>>builder().result(service.forRental(id,actor())).build();}
    @PostMapping("/rental-requests/{id}/complaints") public ApiResponse<ComplaintView> open(@PathVariable String id,@Valid @RequestBody ComplaintOpen p){return result(service.open(id,actor(),p));}
    @GetMapping("/complaints/{id}") public ApiResponse<ComplaintView> get(@PathVariable String id){return result(service.get(id,actor()));}
    @PostMapping("/complaints/{id}/actions/{action}") public ApiResponse<ComplaintView> act(@PathVariable String id,@PathVariable String action,@Valid @RequestBody ComplaintAction p){return result(service.act(id,actor(),action,p));}
    private ApiResponse<ComplaintView> result(ComplaintView value){return ApiResponse.<ComplaintView>builder().result(value).build();}
    private String actor(){var c=UserContextHolder.get();if(c==null||c.userId()==null||c.userId().isBlank())throw new AppException(ErrorCode.UNAUTHENTICATED);return c.userId();}
}
