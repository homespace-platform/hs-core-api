package com.hs.contract.service;

import com.hs.contract.service.impl.ContractServiceImpl;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.payment.event.OnboardingReceiptConfirmed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/** Immediate post-commit attempt plus durable retries from the onboarding sweep. */
@Service @Slf4j
public class AutomaticContractDrafts {
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.hs.contract.deposit.DepositAgreementService> deposits;
    private final ObjectProvider<ContractServiceImpl> contracts;
    private final TaskExecutor executor;

    public AutomaticContractDrafts(ObjectProvider<ContractServiceImpl> contracts,
            @Qualifier("contractDraftExecutor") TaskExecutor executor) {
        this.contracts = contracts;
        this.executor = executor;
    }

    @TransactionalEventListener
    public void receiptConfirmed(OnboardingReceiptConfirmed event) {
        try {
            // Rendering must not delay or time out the HTTP receipt confirmation after money committed.
            executor.execute(() -> tryCreate(event.rentalRequestId()));
        } catch (RuntimeException unavailable) {
            log.warn("AUTOMATIC_CONTRACT_DRAFT_DISPATCH_FAILED rental={}; onboarding sweep will retry", event.rentalRequestId(), unavailable);
        }
    }

    public void tryCreate(String rentalRequestId) {
        if(deposits!=null) try {
            var c=deposits.getObject().createAutomatically(rentalRequestId);
            if(c!=null && c.getStatus()==ContractStatus.DRAFT) contracts.getObject().prepareDepositPreviewAutomatically(rentalRequestId,c.getId());
        } catch(Exception failure) { log.error("AUTOMATIC_DEPOSIT_DOCUMENT_FAILED rental={}; receipt remains confirmed; retry available",rentalRequestId,failure); }
        try {
            var service = contracts.getObject();
            var draft = service.createDraftAutomatically(rentalRequestId);
            if (draft == null || draft.getStatus() != ContractStatus.DRAFT) return;
            // The draft has its own committed transaction. A PDF/storage outage cannot erase it or receipts.
            service.prepareDraftPreviewAutomatically(rentalRequestId, draft.getId());
        } catch (Exception failure) {
            // Funds are already committed. Missing templates/snapshot outages must not undo receipt.
            log.error("AUTOMATIC_CONTRACT_DRAFT_FAILED rental={}; will retry in onboarding sweep", rentalRequestId, failure);
        }
    }
}
