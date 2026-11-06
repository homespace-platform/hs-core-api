package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractPaymentReceiptContextTest {
  @Test void monthlyReceiptIncludesBothCostPeriodAndRentActuallyCovered(){
    var contracts=mock(ContractRepository.class);var revisions=mock(ContractRevisionRepository.class);var invoices=mock(MonthlyInvoiceRepository.class);
    var context=new ContractPaymentReceiptContext(contracts,revisions,invoices,mock(RentalRequestRepository.class),new ObjectMapper());
    var payment=PaymentRequest.builder().contractId("lease").invoiceId("bill").payerId("tenant").payeeId("owner").direction(PaymentDirection.TENANT_TO_LANDLORD).type(PaymentType.MONTHLY_RENT).totalAmount(new BigDecimal("2000000")).build();
    when(contracts.findById("lease")).thenReturn(Optional.of(Contract.builder().id("lease").contractNumber("HD-TEST").currentRevisionId("rev").landlordId("owner").tenantId("tenant").build()));
    when(revisions.findById("rev")).thenReturn(Optional.of(ContractRevision.builder().propertySnapshot("{\"fullAddress\":\"Địa chỉ tại lúc xác nhận\"}").landlordSnapshot("{\"fullName\":\"Chủ nhà\"}").tenantSnapshot("{\"fullName\":\"Người thuê\"}").build()));
    when(invoices.findById("bill")).thenReturn(Optional.of(MonthlyInvoice.builder().contractId("lease").periodStart(LocalDate.of(2026,11,7)).periodEndExclusive(LocalDate.of(2026,12,7))
        .rentPeriodStart(LocalDate.of(2026,12,7)).rentPeriodEndExclusive(LocalDate.of(2027,3,7)).build()));
    var result=context.context(payment);assertEquals("HD-TEST",result.contractNumber());assertEquals("Địa chỉ tại lúc xác nhận",result.propertyAddress());
    assertEquals("Chi phí: 07/11/2026 - trước 07/12/2026; tiền thuê: 07/12/2026 - trước 07/03/2027",result.periodLabel());
    assertTrue(result.amountWords().toLowerCase().contains("hai triệu"));
  }
}
