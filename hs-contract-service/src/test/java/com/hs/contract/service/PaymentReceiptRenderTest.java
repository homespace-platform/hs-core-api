package com.hs.contract.service;

import static org.junit.jupiter.api.Assertions.*;

import com.hs.contract.service.engine.VietnameseCurrencyTextConverter;
import com.hs.payment.dto.BankAccountSnapshotDto;
import com.hs.payment.model.constant.*;
import com.hs.payment.receipt.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Renders the production HTML and real local logo/fonts, not a separate mock design. */
@EnabledIfEnvironmentVariable(
    named = "HS_RECEIPT_RENDER_URL",
    matches = "http://127\\.0\\.0\\.1:[0-9]+")
class PaymentReceiptRenderTest {
  @Test
  void realVietnamesePdfAndLongTableAreReadable() throws Exception {
    var renderer = new PaymentReceiptPdfRenderer(System.getenv("HS_RECEIPT_RENDER_URL"), true);
    var output =
        Path.of(
            System.getenv()
                .getOrDefault("HS_RECEIPT_SAMPLE_DIR", "target/payment-receipt-samples"));
    Files.createDirectories(output);
    for (String kind : List.of("payment", "refund", "multipage")) {
      boolean refund = kind.equals("refund"), longTable = kind.equals("multipage");
      var owner =
          new PaymentReceiptSnapshot.Party(
              "example-owner",
              "Trần Thị Hoài Thương",
              new BankAccountSnapshotDto(
                  "970422", "MB", "Ngân hàng Quân Đội", "000000000001", "TRAN THI HOAI THUONG"));
      var tenant =
          new PaymentReceiptSnapshot.Party(
              "example-tenant",
              "Nguyễn Minh An",
              new BankAccountSnapshotDto(
                  "970436",
                  "VCB",
                  "Ngân hàng TMCP Ngoại Thương Việt Nam",
                  "000000000002",
                  "NGUYEN MINH AN"));
      var rows = new ArrayList<PaymentReceiptSnapshot.Line>();
      rows.add(
          new PaymentReceiptSnapshot.Line(
              refund ? "Hoàn tiền thuê kỳ đầu" : "Tiền thuê kỳ đầu",
              new BigDecimal("2000000"),
              1,
              new BigDecimal("2000000"),
              "Từ 07/11/2026 đến trước 07/12/2026"));
      rows.add(
          new PaymentReceiptSnapshot.Line(
              refund ? "Hoàn tiền đặt cọc" : "Tiền đặt cọc giữ phòng",
              new BigDecimal("1000000"),
              1,
              new BigDecimal("1000000"),
              refund
                  ? "Hoàn theo thỏa thuận hủy hồ sơ trước khi thuê"
                  : "Theo điều kiện đặt cọc đã thống nhất"));
      if (longTable)
        for (int i = 1; i <= 55; i++)
          rows.add(
              new PaymentReceiptSnapshot.Line(
                  "Khoản chi phí kiểm thử ngắt trang số " + i,
                  new BigDecimal("25000"),
                  1,
                  new BigDecimal("25000"),
                  "Dữ liệu minh họa: điện, nước và dịch vụ theo kỳ; kiểm tra đầy đủ dấu tiếng Việt,"
                      + " bảng nhiều dòng."));
      BigDecimal total =
          rows.stream()
              .map(PaymentReceiptSnapshot.Line::amount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      var snapshot =
          new PaymentReceiptSnapshot(
              1,
              "HS-" + (refund ? "HT" : "TT") + "-MAU-20261107",
              "00000000-0000-0000-0000-000000000001",
              "example-rental",
              refund ? PaymentType.INITIAL_PAYMENT_REFUND : PaymentType.INITIAL,
              refund ? PaymentDirection.LANDLORD_TO_TENANT : PaymentDirection.TENANT_TO_LANDLORD,
              total,
              "VND",
              Instant.parse("2026-11-07T02:15:30Z"),
              Instant.parse("2026-11-07T02:10:00Z"),
              "DU-LIEU-MINH-HOA",
              null,
              refund ? owner : tenant,
              refund ? tenant : owner,
              new PaymentReceiptSnapshot.Context(
                  "HD-MAU-20261107",
                  "Phòng P.302, 12 đường Nguyễn Văn Bảo, phường Hạnh Thông, Thành phố Hồ Chí Minh",
                  "P.302",
                  "07/11/2026 - trước 07/12/2026",
                  owner.name(),
                  tenant.name(),
                  VietnameseCurrencyTextConverter.toWords(total)),
              rows);
      byte[] pdf = renderer.render(snapshot, PaymentReceiptService.hash("sample-data-only"));
      Path file =
          longTable
              ? Path.of("target", "isolated", "receipt-qa", "multipage.pdf")
              : output.resolve(
                  kind.equals("payment")
                      ? "HomeSpace-phieu-thanh-toan-mau.pdf"
                      : "HomeSpace-phieu-hoan-tien-mau.pdf");
      Files.createDirectories(file.getParent());
      Files.write(file, pdf);
      try (var parsed = Loader.loadPDF(pdf)) {
        String text = new PDFTextStripper().getText(parsed);
        assertTrue(text.contains("Nguyễn Minh An"));
        assertTrue(text.contains("Trần Thị Hoài Thương"));
        assertTrue(text.contains("DU-LIEU-MINH-HOA"));
        assertTrue(text.contains("3.000.000") || longTable);
        assertTrue(text.contains(refund ? "TỔNG TIỀN ĐÃ HOÀN" : "TỔNG TIỀN ĐÃ THANH TOÁN"));
        if (longTable) {
          assertTrue(parsed.getNumberOfPages() > 1);
          assertTrue(text.contains("số 55"));
        } else assertEquals(1, parsed.getNumberOfPages(), "Standard receipt must fit one A4 page");
      }
    }
  }
}
