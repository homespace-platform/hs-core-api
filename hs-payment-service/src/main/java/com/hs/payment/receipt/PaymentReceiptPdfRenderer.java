package com.hs.payment.receipt;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

@Component
@Slf4j
public class PaymentReceiptPdfRenderer {
  private final String endpoint;
  private final boolean enabled;
  private final RestTemplate client;
  private final SpringTemplateEngine engine;
  public static final List<String> ASSETS =
      List.of(
          "logo.png",
          "inter-latin.woff2",
          "inter-latin-ext.woff2",
          "inter-vietnamese.woff2",
          "footer.html");

  public PaymentReceiptPdfRenderer(
      @Value("${payment.receipts.gotenberg.url:${contract.gotenberg.url:http://localhost:3001}}")
          String url,
      @Value("${payment.receipts.gotenberg.enabled:${contract.gotenberg.enabled:false}}")
          boolean enabled) {
    this.endpoint = url.replaceAll("/+$", "") + "/forms/chromium/convert/html";
    this.enabled = enabled;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(45));
    client = new RestTemplate(factory);
    engine = new SpringTemplateEngine();
    var resolver = new StringTemplateResolver();
    resolver.setTemplateMode("HTML");
    resolver.setCacheable(false);
    engine.setTemplateResolver(resolver);
  }

  public String html(PaymentReceiptSnapshot s, String hash) {
    var context = new Context(Locale.forLanguageTag("vi-VN"));
    boolean refund =
        s.direction() == com.hs.payment.model.constant.PaymentDirection.LANDLORD_TO_TENANT;
    context.setVariable("receipt", s);
    context.setVariable("refund", refund);
    context.setVariable("hash", hash);
    context.setVariable("title", refund ? "PHIẾU XÁC NHẬN HOÀN TIỀN" : "PHIẾU XÁC NHẬN THANH TOÁN");
    context.setVariable("money", money(s.total(), s.currency()));
    context.setVariable("confirmed", stamp(s.confirmedAt()));
    context.setVariable("reported", stamp(s.reportedAt()));
    context.setVariable(
        "rows",
        s.lines().stream()
            .map(
                l -> {
                  var row = new LinkedHashMap<String, Object>();
                  row.put("name", l.name());
                  row.put("description", l.description());
                  row.put(
                      "quantity", l.quantity() == null || l.quantity() < 1 ? "—" : l.quantity());
                  row.put("unit", l.unitPrice() == null ? "—" : money(l.unitPrice(), s.currency()));
                  row.put("amount", money(l.amount(), s.currency()));
                  return row;
                })
            .toList());
    return engine.process(resource("template.html"), context);
  }

  public byte[] render(PaymentReceiptSnapshot snapshot, String hash) {
    if (!enabled) throw new IllegalStateException("Receipt PDF converter is disabled");
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("files", file("index.html", html(snapshot, hash).getBytes(StandardCharsets.UTF_8)));
    for (var asset : ASSETS) body.add("files", file(asset, bytes("assets/" + asset)));
    body.add("preferCssPageSize", "true");
    body.add("printBackground", "true");
    body.add("marginTop", "0.65");
    body.add("marginBottom", "0.65");
    body.add("marginLeft", "0.63");
    body.add("marginRight", "0.63");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    byte[] pdf = client.postForObject(endpoint, new HttpEntity<>(body, headers), byte[].class);
    if (pdf == null
        || pdf.length < 5
        || pdf.length > 25 * 1024 * 1024
        || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
      throw new IllegalStateException("Invalid PDF response");
    return pdf;
  }

  private ByteArrayResource file(String name, byte[] data) {
    return new ByteArrayResource(data) {
      @Override
      public String getFilename() {
        return name;
      }
    };
  }

  public static String resource(String name) {
    return new String(bytes(name), StandardCharsets.UTF_8);
  }

  public static byte[] bytes(String name) {
    try {
      return new ClassPathResource("payment-receipt/" + name).getContentAsByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Missing receipt asset: " + name, e);
    }
  }

  private static String money(BigDecimal amount, String currency) {
    return NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")).format(amount)
        + " "
        + ("VND".equals(currency) ? "đ" : currency);
  }

  private static String stamp(Instant instant) {
    return instant == null
        ? "Không có khai báo riêng"
        : DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(instant);
  }
}
