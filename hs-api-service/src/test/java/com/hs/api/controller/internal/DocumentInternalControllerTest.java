package com.hs.api.controller.internal;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.hs.api.advice.handler.GlobalException;
import com.hs.common.advice.entity.AppException;
import com.hs.common.document.InternalDocumentError;
import com.hs.common.document.InternalDocumentFile;
import com.hs.contract.service.LeaseBundleDocumentService;
import com.hs.payment.receipt.MembershipReceiptService;
import com.hs.payment.receipt.PaymentReceiptService;

class DocumentInternalControllerTest {
    private final PaymentReceiptService rental = mock(PaymentReceiptService.class);
    private final MembershipReceiptService membership = mock(MembershipReceiptService.class);
    private final LeaseBundleDocumentService leases = mock(LeaseBundleDocumentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentInternalController(rental, membership, leases))
            .setControllerAdvice(new GlobalException()).build();
    private static final byte[] PDF = "%PDF-1.7".getBytes();

    private static InternalDocumentFile file(String name) {
        return new InternalDocumentFile(name, PDF);
    }

    @Test void rentalReceiptHappyPathWrapsBase64InEnvelope() throws Exception {
        when(rental.pdfForRecipient("pay-1", "tenant")).thenReturn(file("HS-TT-1.pdf"));
        mvc.perform(get("/internal/documents/rental-receipts/pay-1").param("recipientId", "tenant"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.files.length()").value(1))
                .andExpect(jsonPath("$.result.files[0].fileName").value("HS-TT-1.pdf"))
                .andExpect(jsonPath("$.result.files[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.result.files[0].contentBase64").value(Base64.getEncoder().encodeToString(PDF)))
                .andExpect(jsonPath("$.result.files[0].sizeBytes").value(PDF.length));
    }

    @Test void membershipAndOverpaymentUseTheirOwnKind() throws Exception {
        when(membership.pdfForRecipient("order", "buyer", false)).thenReturn(file("m.pdf"));
        when(membership.pdfForRecipient("over", "buyer", true)).thenReturn(file("o.pdf"));
        mvc.perform(get("/internal/documents/membership-receipts/order").param("recipientId", "buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.files[0].fileName").value("m.pdf"));
        mvc.perform(get("/internal/documents/overpayment-receipts/over").param("recipientId", "buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.files[0].fileName").value("o.pdf"));
    }

    @Test void leaseBundleReturnsLeaseAndDeposit() throws Exception {
        when(leases.bundle("lease", "tenant")).thenReturn(List.of(file("hop-dong-thue.pdf"), file("hop-dong-coc.pdf")));
        mvc.perform(get("/internal/documents/lease-bundles/lease").param("recipientId", "tenant"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.files.length()").value(2))
                .andExpect(jsonPath("$.result.files[1].fileName").value("hop-dong-coc.pdf"));
    }

    @Test void notReadyIs409() throws Exception {
        when(leases.bundle("lease", "tenant")).thenThrow(InternalDocumentError.NOT_READY.exception());
        mvc.perform(get("/internal/documents/lease-bundles/lease").param("recipientId", "tenant"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("NOT_READY"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test void wrongRecipientIs403AndNeverReturnsFiles() throws Exception {
        when(rental.pdfForRecipient("pay-1", "intruder")).thenThrow(InternalDocumentError.RECIPIENT_FORBIDDEN.exception());
        mvc.perform(get("/internal/documents/rental-receipts/pay-1").param("recipientId", "intruder"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test void unknownIdOrKindIs404() throws Exception {
        when(membership.pdfForRecipient("nope", "buyer", false)).thenThrow(InternalDocumentError.NOT_FOUND.exception());
        mvc.perform(get("/internal/documents/membership-receipts/nope").param("recipientId", "buyer"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/documents/unknown-kind/x").param("recipientId", "buyer")).andExpect(status().isNotFound());
    }

    @Test void integrityFailureIs422() throws Exception {
        when(leases.bundle("lease", "tenant")).thenThrow(InternalDocumentError.INTEGRITY_FAILED.exception());
        mvc.perform(get("/internal/documents/lease-bundles/lease").param("recipientId", "tenant"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test void bundleOverThirtyFiveMegabytesIs413() throws Exception {
        byte[] half = new byte[18 * 1024 * 1024];
        when(leases.bundle("lease", "tenant")).thenReturn(List.of(new InternalDocumentFile("a.pdf", half), new InternalDocumentFile("b.pdf", half)));
        mvc.perform(get("/internal/documents/lease-bundles/lease").param("recipientId", "tenant"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.result").doesNotExist());
        byte[] exact = new byte[35 * 1024 * 1024];
        when(leases.bundle("lease", "landlord")).thenReturn(List.of(new InternalDocumentFile("a.pdf", exact)));
        mvc.perform(get("/internal/documents/lease-bundles/lease").param("recipientId", "landlord")).andExpect(status().isOk());
    }

    @Test void missingRecipientIsRejectedBeforeAnyLookup() throws Exception {
        mvc.perform(get("/internal/documents/rental-receipts/pay-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/documents/rental-receipts/pay-1").param("recipientId", " ")).andExpect(status().isBadRequest());
        verify(rental, never()).pdfForRecipient(anyString(), anyString());
        verify(membership, never()).pdfForRecipient(anyString(), anyString(), anyBoolean());
    }
}
