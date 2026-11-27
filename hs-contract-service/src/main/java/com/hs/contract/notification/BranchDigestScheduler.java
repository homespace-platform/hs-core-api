package com.hs.contract.notification;

import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchAttentionResponse.BranchCount;
import com.hs.contract.service.BranchAttentionService;
import com.hs.listing.repository.PropertyBranchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 08:00 Vietnam time on the real clock: one notice per owner whose attention list is not empty. Not transactional:
 * each owner is read in the attention list's own read-only snapshot, then written in a separate short transaction,
 * one owner (and at most one connection) at a time. A missed run is not made up.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class BranchDigestScheduler {
    private static final int PAGE = 100;

    private final PropertyBranchRepository branches;
    private final BranchAttentionService attention;
    private final BranchDigestOutboxWriter writer;

    // shortcut: occupies one of the 4 shared scheduler threads for the whole run; give it its own executor
    // if a run ever takes minutes.
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Ho_Chi_Minh")
    public void sendDaily() {
        send(LocalDate.now(BillingTime.ZONE));
    }

    /** @param day the Vietnam day the run started, which is what makes a notice unique. */
    void send(LocalDate day) {
        String after = "";
        List<String> owners;
        do {
            owners = branches.findActiveOwnerIds(after, PageRequest.of(0, PAGE));
            owners.forEach(ownerId -> sendOwner(ownerId, day));
            if (!owners.isEmpty()) after = owners.getLast();
        } while (owners.size() == PAGE);
    }

    /** A failure stays with its owner. */
    private void sendOwner(String ownerId, LocalDate day) {
        try {
            int count = attention.summary(ownerId).branches().stream().mapToInt(BranchCount::count).sum();
            if (count == 0) return;
            try {
                writer.write(ownerId, day, count);
            } catch (RuntimeException firstFailure) {
                writer.write(ownerId, day, count); // same event id, new transaction
            }
        } catch (RuntimeException failure) {
            log.warn("Branch digest skipped for owner {} ({})", ownerId, failure.getClass().getSimpleName());
        }
    }
}
