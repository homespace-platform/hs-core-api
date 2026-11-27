package com.hs.listing.membership;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Membership time is {@link BillingTime}. Remaining uses are derived from membership_periods rows, so neither
 * the 30-day FREE refill nor paid or add-on expiry needs a job.
 */
@Service
@RequiredArgsConstructor
public class MembershipService {
    public static final int PERIOD_DAYS = 30;
    private static final Duration PERIOD = Duration.ofDays(PERIOD_DAYS);
    private static final Duration UPGRADE_WINDOW = Duration.ofHours(24);
    private static final int ADDON_UNIT_PRICE_VND = 8_000;
    private static final int ADDON_MAX_QUANTITY = 10;
    public static final int ADDON_VALID_DAYS = 90;

    private final MembershipPeriodRepository periods;
    private final MembershipEventRepository events;
    private final UserRepository users;
    private final BillingTime time;

    public record Plan(String code, int publicationUses, int durationDays, int priceVnd) {}
    /** remainingUses and reservedUses are plan-only; the addon fields cover unexpired add-on rows. */
    public record View(String tier, int remainingUses, int reservedUses, Instant expiresAt, int freeUsed,
                       boolean unlimited, Instant quotaResetsAt, int addonRemaining, int addonReserved,
                       Instant addonExpiresAt) {}
    public record Quote(String tier, int amountVnd, String mode, int remainingUsesAfter,
                        Instant expiresAtAfter, Instant upgradeDeadline, boolean upgrade) {}
    public record AddonOffer(int unitPriceVnd, int validDays, int maxQuantity) {}

    public List<Plan> plans() {
        return Arrays.stream(MembershipTier.values())
                .map(t -> new Plan(t.name(), t.uses, (int) PERIOD.toDays(), t.priceVnd)).toList();
    }

    public AddonOffer addonOffer() {
        return new AddonOffer(ADDON_UNIT_PRICE_VND, ADDON_VALID_DAYS, ADDON_MAX_QUANTITY);
    }

    public int addonAmountVnd(int quantity) {
        if (quantity < 1 || quantity > ADDON_MAX_QUANTITY)
            throw new AppException(ListingErrorCode.MEMBERSHIP_ADDON_QUANTITY_INVALID);
        return quantity * ADDON_UNIT_PRICE_VND;
    }

    /** Payment gateways run on the real clock; converts a membership deadline for use there. */
    public Instant realTimeOf(Instant membershipTime) {
        return Instant.now().plus(Duration.between(now(), membershipTime));
    }

    @Transactional
    public void lockOwner(String ownerId) {
        locked(ownerId);
    }

    @Transactional(readOnly = true)
    public View view(String ownerId) {
        User owner = users.findById(ownerId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_OWNER_NOT_FOUND));
        Instant now = now();
        MembershipPeriod free = freePeriod(owner, now);
        MembershipPeriod paid = activePaid(ownerId, now).orElse(null);
        MembershipPeriod plan = paid != null ? paid : free;
        List<MembershipPeriod> addons = periods.findActiveAddons(ownerId, now);
        return new View(plan.getTier().name(), plan.remaining(), plan.getReserved(),
                paid != null ? paid.getEndAt() : null, free.getUsed(), false,
                paid != null ? null : free.getEndAt(),
                addons.stream().mapToInt(MembershipPeriod::remaining).sum(),
                addons.stream().mapToInt(MembershipPeriod::getReserved).sum(),
                addons.stream().filter(p -> p.remaining() > 0).map(MembershipPeriod::getEndAt)
                        .min(Instant::compareTo).orElse(null));
    }

    @Transactional
    public Quote quote(String ownerId, MembershipTier target) {
        if (target == MembershipTier.FREE) throw new AppException(ListingErrorCode.MEMBERSHIP_INVALID_TIER);
        locked(ownerId);
        Instant now = now();
        MembershipPeriod paid = activePaid(ownerId, now).orElse(null);
        if (paid != null) {
            ListingErrorCode refusal = purchaseRefusal(paid, target);
            if (refusal != null) throw new AppException(refusal);
        }
        Instant upgradeDeadline = paid == null ? null : paid.getStartAt().plus(UPGRADE_WINDOW);
        boolean upgrade = paid != null && target.ordinal() > paid.getTier().ordinal() && now.isBefore(upgradeDeadline);
        return new Quote(target.name(), upgrade ? target.priceVnd - paid.getPriceVnd() : target.priceVnd,
                upgrade ? "UPGRADE" : "NEW", target.uses - (upgrade ? paid.getReserved() : 0),
                now.plus(PERIOD), upgradeDeadline, upgrade);
    }

    /**
     * Grants a paid order. Eligibility (upgrade window, same-tier rule) was decided when the order was quoted, and the
     * gateway already limits when it can be paid, so money received is not refused because uses or time moved since.
     */
    @Transactional
    public boolean activate(String ownerId, MembershipTier target, boolean upgrade, String orderId) {
        locked(ownerId);
        Instant now = now();
        MembershipPeriod paid = activePaid(ownerId, now).orElse(null);
        if (upgrade) {
            if (paid == null || paid.getTier().ordinal() >= target.ordinal()) return false;
            MembershipTier oldTier = paid.getTier();
            int before = paid.remaining();
            // start_at stays, so the upgrade window still counts from the original purchase.
            paid.setTier(target);
            paid.setQuota(target.uses);
            paid.setUsed(0); // An upgrade starts the new plan's uses over.
            paid.setPriceVnd(target.priceVnd);
            paid.setEndAt(now.plus(PERIOD));
            periods.save(paid);
            record(MembershipEvent.Type.UPGRADE, paid.remaining() - before, paid, null, orderId, oldTier, target);
            return true;
        }
        if (paid != null) {
            if (target.ordinal() < paid.getTier().ordinal()) return false;
            paid.setEndAt(now); // Only one paid row may be active; its reservations stay on it.
            periods.save(paid);
        }
        MembershipPeriod next = periods.save(period(ownerId, MembershipKind.PLAN, target, now, now.plus(PERIOD),
                target.uses, target.priceVnd));
        record(MembershipEvent.Type.PURCHASE, next.getQuota(), next, null, orderId, null, null);
        return true;
    }

    /** Adds its own row and never reads or edits a plan row. */
    @Transactional
    public boolean activateAddon(String ownerId, int quantity, String orderId) {
        if (quantity < 1 || quantity > ADDON_MAX_QUANTITY) return false;
        locked(ownerId);
        Instant now = now();
        MembershipPeriod addon = periods.save(period(ownerId, MembershipKind.ADDON, null, now,
                now.plus(Duration.ofDays(ADDON_VALID_DAYS)), quantity, addonAmountVnd(quantity)));
        record(MembershipEvent.Type.PURCHASE, quantity, addon, null, orderId, null, null);
        return true;
    }

    @Transactional
    public void reserve(Listing listing, boolean adminActor) {
        if (listing.getPublicationReservation() != null) return;
        if (adminActor) {
            listing.setPublicationReservation("ADMIN");
            return;
        }
        User owner = locked(listing.getOwnerId());
        Instant now = now();
        // The current plan first (an exhausted paid plan never falls back to FREE), then add-ons by earliest expiry.
        MembershipPeriod source = activePaid(owner.getId(), now).orElseGet(() -> freePeriod(owner, now));
        if (source.remaining() <= 0)
            source = periods.findActiveAddons(owner.getId(), now).stream()
                    .filter(p -> p.remaining() > 0).findFirst()
                    .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_QUOTA_EXHAUSTED));
        source.setReserved(source.getReserved() + 1);
        source = periods.save(source);
        listing.setPublicationReservation(source.getKind() == MembershipKind.ADDON ? "ADDON"
                : source.getTier() == MembershipTier.FREE ? "FREE" : "PAID");
        listing.setPublicationPeriodId(source.getId());
        recordReserve(source, listing);
    }

    private void recordReserve(MembershipPeriod source, Listing listing) {
        if (listing.getId() != null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            record(MembershipEvent.Type.RESERVE, -1, source, listing.getId(), null, null, null);
            return;
        }
        // A new listing gets its ID when it is saved, later in this same transaction.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                record(MembershipEvent.Type.RESERVE, -1, source, listing.getId(), null, null, null);
            }
        });
    }

    @Transactional
    public void finish(Listing listing, boolean approved) {
        String source = listing.getPublicationReservation();
        if (source == null) throw new AppException(ListingErrorCode.MEMBERSHIP_RESERVATION_REQUIRED);
        if (!source.equals("ADMIN")) {
            locked(listing.getOwnerId());
            // Always the row holding the reservation, even if it has since ended or been replaced.
            MembershipPeriod held = Optional.ofNullable(listing.getPublicationPeriodId())
                    .flatMap(periods::findById)
                    .filter(p -> Objects.equals(p.getOwnerId(), listing.getOwnerId()) && p.getReserved() > 0)
                    .orElseThrow(() -> new AppException(ListingErrorCode.MEMBERSHIP_RESERVATION_REQUIRED));
            held.setReserved(held.getReserved() - 1);
            if (approved) held.setUsed(held.getUsed() + 1);
            periods.save(held);
            record(approved ? MembershipEvent.Type.CONSUME : MembershipEvent.Type.RELEASE, approved ? 0 : 1,
                    held, listing.getId(), null, null, null);
        }
        listing.setPublicationReservation(null);
        listing.setPublicationPeriodId(null);
    }

    private User locked(String ownerId) {
        // The user row is the per-owner lock for every membership write.
        return users.lockMembershipOwner(ownerId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_OWNER_NOT_FOUND));
    }

    private Instant now() {
        return time.now().truncatedTo(ChronoUnit.MICROS); // Database precision.
    }

    private Optional<MembershipPeriod> activePaid(String ownerId, Instant now) {
        return periods.findActivePaid(ownerId, now).stream().findFirst();
    }

    /** The stored FREE row of the current 30-day period, or an unsaved empty one. */
    private MembershipPeriod freePeriod(User owner, Instant now) {
        Instant anchor = Objects.requireNonNull(owner.getCreatedAt(), "user createdAt");
        long k = Math.max(0, Math.floorDiv(Duration.between(anchor, now).toMillis(), PERIOD.toMillis()));
        Instant start = anchor.plus(PERIOD.multipliedBy(k));
        return periods.findByOwnerIdAndFreeStartAt(owner.getId(), start).orElseGet(() -> {
            MembershipPeriod free = period(owner.getId(), MembershipKind.PLAN, MembershipTier.FREE, start,
                    start.plus(PERIOD), MembershipTier.FREE.uses, 0);
            free.setFreeStartAt(start);
            return free;
        });
    }

    private static ListingErrorCode purchaseRefusal(MembershipPeriod active, MembershipTier target) {
        if (target.ordinal() < active.getTier().ordinal()) return ListingErrorCode.MEMBERSHIP_DOWNGRADE_NOT_ALLOWED;
        if (target == active.getTier() && active.remaining() > 0) return ListingErrorCode.MEMBERSHIP_SAME_TIER_ACTIVE;
        return null;
    }

    private static MembershipPeriod period(String ownerId, MembershipKind kind, MembershipTier tier,
                                           Instant start, Instant end, int quota, int priceVnd) {
        MembershipPeriod p = new MembershipPeriod();
        p.setOwnerId(ownerId);
        p.setKind(kind);
        p.setTier(tier);
        p.setStartAt(start);
        p.setEndAt(end);
        p.setQuota(quota);
        p.setPriceVnd(priceVnd);
        return p;
    }

    private void record(MembershipEvent.Type type, int delta, MembershipPeriod period, String listingId,
                        String orderId, MembershipTier oldTier, MembershipTier newTier) {
        events.save(new MembershipEvent(null, type, delta, period.getId(), listingId, orderId, oldTier, newTier, now()));
    }
}
