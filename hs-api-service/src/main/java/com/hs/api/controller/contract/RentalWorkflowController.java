package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.billing.*;
import com.hs.contract.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping({"/rental-requests/{id}/workflow","/api/v1/rental-requests/{id}/workflow"})
public class RentalWorkflowController {
    private final PreTenancyResolutionService cancellation;
    private final MoveInHandoverService handover;
    private final MutualTerminationService termination;
    private final WorkflowDocumentService documents;
    private String actor(){var c=UserContextHolder.get();if(c==null||c.userId()==null||c.userId().isBlank())throw new AppException(ErrorCode.UNAUTHENTICATED);return c.userId();}
    private ApiResponse<RentalWorkflowView> ok(RentalWorkflowView v){return ApiResponse.<RentalWorkflowView>builder().result(v).build();}
    @GetMapping("/{kind}/documents") public ApiResponse<java.util.List<WorkflowDocumentService.View>> documents(@PathVariable String id,@PathVariable String kind){return ApiResponse.<java.util.List<WorkflowDocumentService.View>>builder().result(documents.list(id,kind,actor())).build();}
    @PostMapping("/{kind}/documents") public ApiResponse<WorkflowDocumentService.View> prepareDocument(@PathVariable String id,@PathVariable String kind,@Valid @RequestBody RentalWorkflowPayloads.Action p){return ApiResponse.<WorkflowDocumentService.View>builder().result(documents.prepare(id,kind,actor(),p.expectedRevision())).build();}
    @GetMapping("/cancellation") public ApiResponse<RentalWorkflowView> cancellation(@PathVariable String id){return ok(cancellation.get(id,actor()));}
    @PostMapping("/cancellation") public ApiResponse<RentalWorkflowView> cancellation(@PathVariable String id,@Valid @RequestBody RentalWorkflowPayloads.Cancellation p){return ok(cancellation.propose(id,actor(),p));}
    @PostMapping("/cancellation/{action}") public ApiResponse<RentalWorkflowView> cancellation(@PathVariable String id,@PathVariable String action,@Valid @RequestBody RentalWorkflowPayloads.Action p){return ok(cancellation.act(id,actor(),action,p));}
    @GetMapping("/move-in") public ApiResponse<RentalWorkflowView> handover(@PathVariable String id){return ok(handover.get(id,actor()));}
    @PostMapping("/move-in") public ApiResponse<RentalWorkflowView> handover(@PathVariable String id,@Valid @RequestBody RentalWorkflowPayloads.Handover p){return ok(handover.propose(id,actor(),p));}
    @PostMapping("/move-in/{action}") public ApiResponse<RentalWorkflowView> handover(@PathVariable String id,@PathVariable String action,@Valid @RequestBody RentalWorkflowPayloads.Action p){return ok(handover.act(id,actor(),action,p));}
    @GetMapping("/early-exit") public ApiResponse<RentalWorkflowView> termination(@PathVariable String id){return ok(termination.get(id,actor()));}
    @GetMapping("/early-exit/calculation") public ApiResponse<FinalRentCalculation> calculate(@PathVariable String id,@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endExclusive,@RequestParam String policy){return ApiResponse.<FinalRentCalculation>builder().result(termination.calculate(id,actor(),endExclusive,policy)).build();}
    @PostMapping("/early-exit") public ApiResponse<RentalWorkflowView> termination(@PathVariable String id,@Valid @RequestBody RentalWorkflowPayloads.Termination p){return ok(termination.propose(id,actor(),p));}
    @PostMapping("/early-exit/{action}") public ApiResponse<RentalWorkflowView> termination(@PathVariable String id,@PathVariable String action,@Valid @RequestBody RentalWorkflowPayloads.Action p){return ok(termination.act(id,actor(),action,p));}
}
