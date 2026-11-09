package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.receipt.PaymentReceiptSnapshot;
import com.hs.payment.receipt.PaymentReceiptPdfRenderer;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractPaymentReceiptContextTest {
  @Test void monthlyReceiptIncludesBothCostPeriodAndRentActuallyCovered(){
    var contracts=mock(ContractRepository.class);var revisions=mock(ContractRevisionRepository.class);var invoices=mock(MonthlyInvoiceRepository.class);
    var context=new ContractPaymentReceiptContext(contracts,revisions,invoices,mock(ContractClosureRepository.class),mock(RentalRequestRepository.class),new ObjectMapper());
    var payment=PaymentRequest.builder().contractId("lease").invoiceId("bill").payerId("tenant").payeeId("owner").direction(PaymentDirection.TENANT_TO_LANDLORD).type(PaymentType.MONTHLY_RENT).totalAmount(new BigDecimal("2000000")).build();
    when(contracts.findById("lease")).thenReturn(Optional.of(Contract.builder().id("lease").contractNumber("HD-TEST").currentRevisionId("rev").landlordId("owner").tenantId("tenant").build()));
    when(revisions.findById("rev")).thenReturn(Optional.of(ContractRevision.builder().propertySnapshot("{\"fullAddress\":\"Địa chỉ tại lúc xác nhận\"}").landlordSnapshot("{\"fullName\":\"Chủ nhà\"}").tenantSnapshot("{\"fullName\":\"Người thuê\"}").build()));
    when(invoices.findById("bill")).thenReturn(Optional.of(MonthlyInvoice.builder().contractId("lease").periodStart(LocalDate.of(2026,11,7)).periodEndExclusive(LocalDate.of(2026,12,7))
        .rentPeriodStart(LocalDate.of(2026,12,7)).rentPeriodEndExclusive(LocalDate.of(2027,3,7)).build()));
    var result=context.context(payment);assertEquals("HD-TEST",result.contractNumber());assertEquals("Địa chỉ tại lúc xác nhận",result.propertyAddress());
    assertEquals("Chi phí: 07/11/2026 - trước 07/12/2026; tiền thuê: 07/12/2026 - trước 07/03/2027",result.periodLabel());
    assertTrue(result.amountWords().toLowerCase().contains("hai triệu"));
  }
  @Test void depositRefundReceiptFreezesAgreedDeductionsAndOriginalDeposit(){
    var closures=mock(ContractClosureRepository.class);
    var context=new ContractPaymentReceiptContext(mock(ContractRepository.class),mock(ContractRevisionRepository.class),
        mock(MonthlyInvoiceRepository.class),closures,mock(RentalRequestRepository.class),new ObjectMapper());
    var payment=PaymentRequest.builder().id("refund").payerId("owner").payeeId("tenant")
        .direction(PaymentDirection.LANDLORD_TO_TENANT).type(PaymentType.DEPOSIT_REFUND)
        .totalAmount(new BigDecimal("2900000")).build();
    when(closures.findByRefundPaymentId("refund")).thenReturn(Optional.of(ContractClosure.builder()
        .refundAmount(new BigDecimal("2900000"))
        .deductionsSnapshot("[{\"description\":\"Vỡ kính\",\"amount\":500000,\"evidenceIds\":[]}]").build()));
    var receiptContext=context.context(payment);
    var settlement=receiptContext.settlement();
    assertEquals(new BigDecimal("3400000"),settlement.depositAmount());
    assertEquals("Vỡ kính",settlement.deductions().getFirst().description());
    assertEquals(new BigDecimal("500000"),settlement.deductions().getFirst().amount());
    var owner=new PaymentReceiptSnapshot.Party("owner","Chủ nhà",null);
    var tenant=new PaymentReceiptSnapshot.Party("tenant","Người thuê",null);
    var receipt=new PaymentReceiptSnapshot(1,"HS-HT-TEST","refund",null,PaymentType.DEPOSIT_REFUND,
        PaymentDirection.LANDLORD_TO_TENANT,new BigDecimal("2900000"),"VND",null,null,null,null,
        owner,tenant,receiptContext,java.util.List.of(new PaymentReceiptSnapshot.Line("Hoàn cọc",new BigDecimal("2900000"),1,new BigDecimal("2900000"),null)));
    var html=new PaymentReceiptPdfRenderer("http://localhost:3001",false).html(receipt,"hash");
    assertTrue(html.contains("Vỡ kính"));
    assertTrue(html.contains("3.400.000"));
    assertTrue(html.contains("500.000"));
    assertTrue(html.contains("2.900.000"));
  }
  @Test void settlementBalanceReceiptShowsDeductionsAboveHeldDeposit(){
    var closures=mock(ContractClosureRepository.class);
    var context=new ContractPaymentReceiptContext(mock(ContractRepository.class),mock(ContractRevisionRepository.class),
        mock(MonthlyInvoiceRepository.class),closures,mock(RentalRequestRepository.class),new ObjectMapper());
    var payment=PaymentRequest.builder().id("balance").payerId("tenant").payeeId("owner")
        .direction(PaymentDirection.TENANT_TO_LANDLORD).type(PaymentType.OTHER_CHARGE)
        .totalAmount(new BigDecimal("100000")).build();
    when(closures.findByBalancePaymentId("balance")).thenReturn(Optional.of(ContractClosure.builder()
        .balanceAmount(new BigDecimal("100000"))
        .deductionsSnapshot("[{\"description\":\"Sửa kính\",\"amount\":3500000,\"evidenceIds\":[]}]").build()));
    assertEquals(new BigDecimal("3400000"),context.context(payment).settlement().depositAmount());
  }
}
