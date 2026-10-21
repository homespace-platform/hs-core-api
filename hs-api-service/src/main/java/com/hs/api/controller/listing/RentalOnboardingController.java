package com.hs.api.controller.listing;
import com.hs.common.context.UserContextHolder;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.service.RentalOnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping({"/rental-requests/{id}/onboarding","/api/v1/rental-requests/{id}/onboarding"}) @RequiredArgsConstructor
public class RentalOnboardingController {
    private final RentalOnboardingService service;
    private String actor(){var c=UserContextHolder.get();if(c==null||c.userId()==null)throw new AppException(ErrorCode.UNAUTHENTICATED);return c.userId();}
    private ApiResponse<RentalOnboardingService.View> ok(RentalOnboardingService.View v){return ApiResponse.<RentalOnboardingService.View>builder().result(v).build();}
    @PostMapping("/default-notice") public ApiResponse<RentalOnboardingService.View> notice(@PathVariable String id,@RequestBody @Valid RentalOnboardingService.Cancellation p){return ok(service.notifyDefault(id,actor(),p));}
    @PostMapping("/default-dispute") public ApiResponse<RentalOnboardingService.View> dispute(@PathVariable String id,@RequestBody @Valid RentalOnboardingService.Cancellation p){return ok(service.disputeDefault(id,actor(),p));}
    @PostMapping("/default-completion") public ApiResponse<RentalOnboardingService.View> complete(@PathVariable String id,@RequestBody @Valid RentalOnboardingService.DefaultCompletion p){return ok(service.completeDefault(id,actor(),p));}
    @PostMapping("/refresh") public ApiResponse<RentalOnboardingService.View> refresh(@PathVariable String id){return ok(service.refresh(id,actor()));}
    @PostMapping("/earlier-start") public ApiResponse<RentalOnboardingService.View> request(@PathVariable String id,@RequestBody @Valid RentalOnboardingService.StartRequest p){return ok(service.requestEarlier(id,actor(),p));}
    @PutMapping("/earlier-start/{changeId}") public ApiResponse<RentalOnboardingService.View> decide(@PathVariable String id,@PathVariable String changeId,@RequestBody @Valid RentalOnboardingService.Decision p){return ok(service.decideEarlier(id,changeId,actor(),p));}
    @DeleteMapping("/earlier-start/{changeId}") public ApiResponse<RentalOnboardingService.View> withdraw(@PathVariable String id,@PathVariable String changeId){return ok(service.withdrawEarlier(id,changeId,actor()));}
    @PostMapping("/cancellation") public ApiResponse<RentalOnboardingService.View> cancel(@PathVariable String id,@RequestBody @Valid RentalOnboardingService.Cancellation p){return ok(service.requestCancellation(id,actor(),p));}
    @PostMapping("/reopen-signing") public ApiResponse<RentalOnboardingService.View> reopen(@PathVariable String id,@RequestBody @Valid RentalOnboardingService.Cancellation p){return ok(service.reopenSigning(id,actor(),p));}
    @PutMapping("/cancellation") public ApiResponse<RentalOnboardingService.View> decideCancel(@PathVariable String id,@RequestBody @Valid RentalOnboardingService.Decision p){return ok(service.decideCancellation(id,actor(),p));}
    @PutMapping("/payments/{paymentId}/review") public ApiResponse<RentalOnboardingService.View> review(@PathVariable String id,@PathVariable String paymentId,@RequestBody @Valid RentalOnboardingService.ReviewDecision p){return ok(service.resolveReview(id,paymentId,actor(),p));}
}
