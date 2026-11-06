package com.hs.api.controller.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.common.dto.PageResponse;
import com.hs.payment.receipt.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaymentReceiptControllerTest {
  final PaymentReceiptService service = mock(PaymentReceiptService.class);
  final PaymentReceiptController controller = new PaymentReceiptController(service);

  @AfterEach
  void cleanup() {
    UserContextHolder.clear();
  }

  @Test
  void missingIdentityNeverReachesReceiptOrPdfService() {
    assertThrows(AppException.class, () -> controller.list(null, null, null, 1, 12));
    assertThrows(AppException.class, () -> controller.pdf("receipt", true));
    verifyNoInteractions(service);
  }

  @Test
  void listingAndRetryAlwaysUseAuthenticatedActor() {
    UserContextHolder.set(new UserContext("tenant", null));
    when(service.list("tenant", "TENANT", "lease", null, 1, 12)).thenReturn(new PageResponse<>());
    controller.list("TENANT", "lease", null, 1, 12);
    controller.byPayment("payment");
    controller.retry("receipt");
    verify(service).list("tenant", "TENANT", "lease", null, 1, 12);
    verify(service).byPayment("payment", "tenant");
    verify(service).retry("receipt", "tenant");
  }

  @Test
  void bothApiPathsServePrivatePdfWithSafeFilenameAndNoCaching() throws Exception {
    UserContextHolder.set(new UserContext("owner", null));
    var view =
        new PaymentReceiptView(
            "r",
            "p",
            "HS-TT-20261107-1234",
            "Phiếu xác nhận thanh toán",
            "PAYMENT",
            BigDecimal.TEN,
            "VND",
            Instant.now(),
            "Tenant",
            "Owner",
            null,
            null,
            null,
            "HSREF",
            PaymentReceipt.PdfStatus.READY,
            "hash");
    when(service.get("r", "owner")).thenReturn(view);
    when(service.pdf("r", "owner"))
        .thenReturn("%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    for (String base : new String[] {"/payment-receipts", "/api/v1/payment-receipts"})
      mvc.perform(get(base + "/r/pdf").param("download", "true"))
          .andExpect(status().isOk())
          .andExpect(content().contentType("application/pdf"))
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(
              header()
                  .string(
                      "Content-Disposition", "attachment; filename=\"HS-TT-20261107-1234.pdf\""));
    assertTrue(
        controller
            .pdf("r", false)
            .getHeaders()
            .getFirst("Content-Disposition")
            .startsWith("inline;"));
  }
}
