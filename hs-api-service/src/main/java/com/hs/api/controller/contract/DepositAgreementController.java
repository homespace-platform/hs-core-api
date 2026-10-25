package com.hs.api.controller.contract;

import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.dto.response.ContractResponse;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.service.impl.ContractServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Same signing endpoints/PDF engine as lease, scoped to a separate deposit envelope. */
@RestController @RequiredArgsConstructor
@RequestMapping({"/rental-requests/{rentalId}/deposit-agreement","/api/v1/rental-requests/{rentalId}/deposit-agreement"})
public class DepositAgreementController {
    private final DepositAgreementService deposits;
    private final ContractServiceImpl contracts;
    public record View(boolean required,boolean signingAllowed,String blockedReason,ContractResponse agreement,List<ContractDocumentResponse> documents) {}
    private String actor(){var c=UserContextHolder.get();if(c==null||c.userId()==null)throw new AppException(ErrorCode.UNAUTHENTICATED);return c.userId();}
    private View read(String rentalId,String actor) {
        var overview=deposits.overview(rentalId,actor);
        String contractId=overview.signingContractId();
        return new View(overview.required(),overview.signingAllowed(),overview.blockedReason(),
            contractId==null?null:contracts.getContract(contractId),
            contractId==null?List.of():contracts.getDocumentsByContract(contractId));
    }
    @GetMapping public ApiResponse<View> view(@PathVariable String rentalId) {
        return ApiResponse.<View>builder().result(read(rentalId,actor())).build();
    }
    @PostMapping("/prepare") public ApiResponse<View> prepare(@PathVariable String rentalId) {
        String actor=actor();deposits.participant(rentalId,actor);
        var c=deposits.createAutomatically(rentalId);
        if(c!=null)contracts.prepareDepositPreviewAutomatically(rentalId,c.getId());
        return ApiResponse.<View>builder().result(read(rentalId,actor)).build();
    }
    @PostMapping("/reopen-signing") public ApiResponse<View> reopen(@PathVariable String rentalId) {
        String actor=actor();deposits.reopen(rentalId,actor);
        return ApiResponse.<View>builder().result(read(rentalId,actor)).build();
    }
}
