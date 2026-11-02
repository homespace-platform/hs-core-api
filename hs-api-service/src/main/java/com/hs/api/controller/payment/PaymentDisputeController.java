package com.hs.api.controller.payment;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.payment.dto.*;
import com.hs.payment.service.PaymentDisputeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
public class PaymentDisputeController {
    private final PaymentDisputeService service;
    public record Adopt(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min=10,max=500) String reason) {}
    @GetMapping("/payment-requests/{id}/dispute-cases")
    public ApiResponse<List<DisputeCaseView>> cases(@PathVariable String id){return ApiResponse.<List<DisputeCaseView>>builder().result(service.forPayment(id,actor())).build();}
    @PostMapping("/payment-requests/{id}/dispute-cases/adopt")
    public ApiResponse<DisputeCaseView> adopt(@PathVariable String id,@Valid @RequestBody Adopt request){return result(service.adoptLegacy(id,actor(),request.reason()));}
    @PostMapping("/payment-disputes/{id}/actions/{action}")
    public ApiResponse<DisputeCaseView> act(@PathVariable String id,@PathVariable String action,@Valid @RequestBody DisputeCaseAction request){return result(service.act(id,actor(),action,request));}
    private ApiResponse<DisputeCaseView> result(DisputeCaseView value){return ApiResponse.<DisputeCaseView>builder().result(value).build();}
    private String actor(){var c=UserContextHolder.get();if(c==null||c.userId()==null||c.userId().isBlank())throw new AppException(ErrorCode.UNAUTHENTICATED);return c.userId();}
}
