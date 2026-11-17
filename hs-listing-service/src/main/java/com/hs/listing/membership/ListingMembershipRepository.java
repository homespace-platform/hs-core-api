package com.hs.listing.membership;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingMembershipRepository extends JpaRepository<ListingMembership, String> {
    @org.springframework.data.jpa.repository.Query("select m.ownerId from ListingMembership m where m.paidExpiresAt is not null "
        + "and m.tier <> com.hs.listing.membership.MembershipTier.FREE and m.paidExpiresAt <= :soon "
        + "and ((m.paidExpiresAt > :now and (m.expiryReminderFor is null or m.expiryReminderFor <> m.paidExpiresAt)) "
        + "or (m.paidExpiresAt <= :now and (m.expiredNoticeFor is null or m.expiredNoticeFor <> m.paidExpiresAt))) order by m.ownerId")
    java.util.List<String> findExpiryNoticeOwnerIds(@org.springframework.data.repository.query.Param("now") java.time.Instant now,
        @org.springframework.data.repository.query.Param("soon") java.time.Instant soon,org.springframework.data.domain.Pageable page);
}
