package com.hs.payment.receipt;

import static org.junit.jupiter.api.Assertions.*;

import com.hs.payment.dto.BankAccountSnapshotDto;
import com.hs.payment.model.constant.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentReceiptTemplateTest {
  @Test
  void realTemplateEscapesDataAndUsesBundledVietnameseFonts() {
    var snapshot =
        new PaymentReceiptSnapshot(
            1,
            "HS-TT-TEST",
            "payment",
            "rental",
            PaymentType.INITIAL,
            PaymentDirection.TENANT_TO_LANDLORD,
            new BigDecimal("3000000"),
            "VND",
            Instant.parse("2026-11-07T00:00:00Z"),
            null,
            "HSREF",
            null,
            new PaymentReceiptSnapshot.Party("tenant", "Nguyễn <script>alert(1)</script>", null),
            new PaymentReceiptSnapshot.Party(
                "owner",
                "Trần Thị Chủ Nhà",
                new BankAccountSnapshotDto(
                    "970422", "MB", "Ngân hàng Quân Đội", "123456", "TRAN THI CHU NHA")),
            PaymentReceiptSnapshot.Context.empty(),
            List.of(
                new PaymentReceiptSnapshot.Line(
                    "Tiền thuê kỳ đầu",
                    new BigDecimal("3000000"),
                    1,
                    new BigDecimal("3000000"),
                    "Điện nước tính riêng")));
    String html =
        new PaymentReceiptPdfRenderer("http://localhost:1", false).html(snapshot, "test-hash");
    assertTrue(html.contains("Nguyễn &lt;script&gt;"));
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("3.000.000 đ"));
    assertTrue(html.contains("07:00:00 07/11/2026"));
    assertTrue(html.contains("PHIẾU XÁC NHẬN THANH TOÁN"));
    assertTrue(html.contains("thead{display:table-header-group}"));
    assertTrue(html.contains("inter-vietnamese.woff2"));
    for (String asset : PaymentReceiptPdfRenderer.ASSETS)
      assertTrue(PaymentReceiptPdfRenderer.bytes("assets/" + asset).length > 20);
    assertThrows(
        IllegalStateException.class,
        () -> new PaymentReceiptPdfRenderer("http://localhost:1", false).render(snapshot, "hash"));
  }
}
