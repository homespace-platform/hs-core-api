package com.hs.listing.membership;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.listing.repository.ListingStatusHistoryRepository;
import com.hs.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MembershipService {
    private final ListingMembershipRepository memberships;
    private final UserRepository users;
    private final ListingStatusHistoryRepository histories;

    public record Plan(String code, int publicationUses, int durationDays, int priceVnd) {}
    public record View(String tier, int remainingUses, int reservedUses, Instant expiresAt, int freeUsed,
                       boolean unlimited) {}
    public record Quote(String tier, int amountVnd, String mode, int remainingUsesAfter,
                        Instant expiresAtAfter, Instant upgradeDeadline, boolean upgrade) {}

    public List<Plan> plans() {
        return Arrays.stream(MembershipTier.values()).map(t -> new Plan(t.name(), t.uses,
                t == MembershipTier.FREE ? 0 : 30, t.priceVnd)).toList();
    }

    @Transactional
    public void lockOwner(String ownerId) {
        users.lockMembershipOwner(ownerId).orElseThrow(() -> new AppException(ListingErrorCode.LISTING_OWNER_NOT_FOUND));
    }

    public View view(String ownerId) {
        var m = memberships.findById(ownerId).orElse(null);
        if (m == null) {
            int used = (int) Math.min(3, histories.countLegacyPublications(ownerId));
            return new View("FREE", isAdmin() ? Integer.MAX_VALUE : 3 - used, 0, null, used, isAdmin());
        }
        boolean paid = active(m, Instant.now());
        int reserved = paid ? m.getPaidReserved() : m.getFreeReserved();
        int remaining = paid ? m.getTier().uses - m.getPaidUsed() - reserved : 3 - m.getFreeUsed() - reserved;
        return new View(paid ? m.getTier().name() : "FREE", isAdmin() ? Integer.MAX_VALUE : Math.max(0, remaining), reserved,
                paid ? m.getPaidExpiresAt() : null, m.getFreeUsed(), isAdmin());
    }

    @Transactional
    public Quote quote(String ownerId, MembershipTier target) {
        if (target == MembershipTier.FREE) throw new AppException(ListingErrorCode.MEMBERSHIP_INVALID_TIER);
        ListingMembership m = locked(ownerId);
        boolean active = active(m, Instant.now());
        if (active && (target.ordinal() < m.getTier().ordinal()
                || (target == m.getTier() && m.getFirstPaidAt() != null
                && Instant.now().isBefore(m.getFirstPaidAt().plus(24, ChronoUnit.HOURS)))))
            throw new AppException(ListingErrorCode.MEMBERSHIP_DOWNGRADE_NOT_ALLOWED);
        boolean upgrade = active && m.getFirstPaidAt() != null
                && Instant.now().isBefore(m.getFirstPaidAt().plus(24, ChronoUnit.HOURS));
        return new Quote(target.name(), upgrade ? target.priceVnd - m.getPaidPriceVnd() : target.priceVnd,
                upgrade ? "UPGRADE" : "NEW", target.uses - (upgrade ? m.getPaidUsed() + m.getPaidReserved() : m.getPaidReserved()),
                Instant.now().plus(30, ChronoUnit.DAYS), active ? m.getFirstPaidAt().plus(24, ChronoUnit.HOURS) : null,
                upgrade);
    }

    @Transactional
    public boolean activate(String ownerId, MembershipTier target, boolean upgrade) {
        return activateAt(ownerId, target, upgrade, Instant.now());
    }

    @Transactional
    public boolean activateAt(String ownerId, MembershipTier target, boolean upgrade, Instant paymentTime) {
        ListingMembership m = locked(ownerId);
        Instant now = Instant.now();
        if (upgrade) {
            if (!active(m, now) || m.getTier().ordinal() >= target.ordinal() || m.getFirstPaidAt() == null
                    || paymentTime == null || !paymentTime.isBefore(m.getFirstPaidAt().plus(24, ChronoUnit.HOURS)))
                return false;
            // The first purchase timestamp deliberately remains unchanged across upgrades.
        } else {
            // Existing pending reviews remain reserved against the replacement plan.
            if (m.getPaidReserved() > target.uses) return false;
            m.setPaidUsed(0);
            m.setFirstPaidAt(now);
        }
        m.setTier(target);
        m.setPaidPriceVnd(target.priceVnd);
        m.setPaidExpiresAt(now.plus(30, ChronoUnit.DAYS));
        memberships.save(m);
        return true;
    }

    @Transactional
    public void reserve(Listing listing, boolean adminActor) {
        if (listing.getPublicationReservation() != null) return;
        if (adminActor || isAdmin()) {
            locked(listing.getOwnerId()); // Establish the owner's historical free count before this exempt publication.
            listing.setPublicationReservation("ADMIN");
            return;
        }
        ListingMembership m = locked(listing.getOwnerId());
        if (active(m, Instant.now())) {
            if (m.getPaidUsed() + m.getPaidReserved() >= m.getTier().uses)
                throw new AppException(ListingErrorCode.LISTING_QUOTA_EXHAUSTED);
            m.setPaidReserved(m.getPaidReserved() + 1);
            listing.setPublicationReservation("PAID");
        } else {
            if (m.getFreeUsed() + m.getFreeReserved() >= 3)
                throw new AppException(ListingErrorCode.LISTING_QUOTA_EXHAUSTED);
            m.setFreeReserved(m.getFreeReserved() + 1);
            listing.setPublicationReservation("FREE");
        }
        memberships.save(m);
    }

    @Transactional
    public void finish(Listing listing, boolean approved) {
        String source = listing.getPublicationReservation();
        if (source == null) throw new AppException(ListingErrorCode.MEMBERSHIP_RESERVATION_REQUIRED);
        if (!source.equals("ADMIN")) {
            ListingMembership m = locked(listing.getOwnerId());
            if (source.equals("PAID")) {
                if (m.getPaidReserved() <= 0) throw new AppException(ListingErrorCode.MEMBERSHIP_RESERVATION_REQUIRED);
                m.setPaidReserved(m.getPaidReserved() - 1);
                if (approved) m.setPaidUsed(m.getPaidUsed() + 1);
            } else if (source.equals("FREE")) {
                if (m.getFreeReserved() <= 0) throw new AppException(ListingErrorCode.MEMBERSHIP_RESERVATION_REQUIRED);
                m.setFreeReserved(m.getFreeReserved() - 1);
                if (approved) m.setFreeUsed(m.getFreeUsed() + 1);
            } else {
                throw new AppException(ListingErrorCode.MEMBERSHIP_RESERVATION_REQUIRED);
            }
            memberships.save(m);
        }
        listing.setPublicationReservation(null);
    }

    private ListingMembership locked(String ownerId) {
        // Lock the existing user row before creating a membership row, including the first free submission.
        users.lockMembershipOwner(ownerId).orElseThrow(() -> new AppException(ListingErrorCode.LISTING_OWNER_NOT_FOUND));
        return memberships.findById(ownerId).orElseGet(() -> {
            ListingMembership m = new ListingMembership();
            m.setOwnerId(ownerId);
            m.setFreeUsed((int) Math.min(3, histories.countLegacyPublications(ownerId)));
            return memberships.saveAndFlush(m);
        });
    }

    private boolean active(ListingMembership m, Instant now) {
        return m.getTier() != null && m.getPaidExpiresAt() != null && m.getPaidExpiresAt().isAfter(now);
    }

    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ADMIN"));
    }
}
