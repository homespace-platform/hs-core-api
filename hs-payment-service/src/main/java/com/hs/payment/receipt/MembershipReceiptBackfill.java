package com.hs.payment.receipt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup job: receipts for PAID orders created before this feature. Idempotent; logs counts only. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipReceiptBackfill {
  private static final int BATCH = 50;
  private final MembershipReceiptService service;

  @Value("${payment.membership-receipts.backfill-enabled:true}")
  private boolean enabled;

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (enabled) Thread.ofPlatform().daemon().name("membership-receipt-backfill").start(this::run);
  }

  /** Returns the number of orders examined; the id cursor guarantees progress even if a row is skipped. */
  public int run() {
    int seen = 0;
    try {
      String after = "";
      while (true) {
        var ids = service.backfillBatch(after, BATCH);
        if (ids.isEmpty()) break;
        seen += ids.size();
        after = ids.get(ids.size() - 1);
      }
      log.info("MEMBERSHIP_RECEIPT_BACKFILL_DONE examined={}", seen);
    } catch (Exception e) {
      log.error("MEMBERSHIP_RECEIPT_BACKFILL_FAILED examined={}", seen, e);
    }
    return seen;
  }
}
