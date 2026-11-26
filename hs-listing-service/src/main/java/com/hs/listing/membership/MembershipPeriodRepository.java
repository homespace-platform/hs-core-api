package com.hs.listing.membership;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MembershipPeriodRepository extends JpaRepository<MembershipPeriod, String> {
    String PLAN = "com.hs.listing.membership.MembershipKind.PLAN";
    String ADDON = "com.hs.listing.membership.MembershipKind.ADDON";
    String FREE = "com.hs.listing.membership.MembershipTier.FREE";

    String ACTIVE_PAID = "select p from MembershipPeriod p where p.ownerId = :ownerId and p.kind = " + PLAN
            + " and p.tier <> " + FREE + " and p.startAt <= :now and p.endAt > :now order by p.startAt desc";
    String ACTIVE_ADDONS = "select p from MembershipPeriod p where p.ownerId = :ownerId and p.kind = " + ADDON
            + " and p.startAt <= :now and p.endAt > :now order by p.endAt, p.startAt, p.id";
    /** Paid plan rows owing a notice. A row replaced by a later-ending paid row never notifies. */
    String DUE_NOTICE = "p.kind = " + PLAN + " and p.tier <> " + FREE + " and p.endAt > :since and p.endAt <= :soon "
            + "and ((p.endAt > :now and (p.expiryReminderFor is null or p.expiryReminderFor <> p.endAt)) "
            + "or (p.endAt <= :now and (p.expiredNoticeFor is null or p.expiredNoticeFor <> p.endAt))) "
            + "and not exists (select n.id from MembershipPeriod n where n.ownerId = p.ownerId and n.kind = " + PLAN
            + " and n.tier <> " + FREE + " and n.endAt > p.endAt)";
    String DUE_OWNERS = "select p.ownerId from MembershipPeriod p where " + DUE_NOTICE
            + " and p.ownerId > :after order by p.ownerId";
    String DUE_FOR_OWNER = "select p from MembershipPeriod p where p.ownerId = :ownerId and " + DUE_NOTICE;

    @Query(ACTIVE_PAID)
    List<MembershipPeriod> findActivePaid(@Param("ownerId") String ownerId, @Param("now") Instant now);

    @Query(ACTIVE_ADDONS)
    List<MembershipPeriod> findActiveAddons(@Param("ownerId") String ownerId, @Param("now") Instant now);

    Optional<MembershipPeriod> findByOwnerIdAndFreeStartAt(String ownerId, Instant freeStartAt);

    @Query(DUE_OWNERS)
    List<String> findExpiryNoticeOwnerIds(@Param("now") Instant now, @Param("soon") Instant soon,
                                          @Param("since") Instant since, @Param("after") String after, Pageable page);

    @Query(DUE_FOR_OWNER)
    List<MembershipPeriod> findExpiryNotices(@Param("ownerId") String ownerId, @Param("now") Instant now,
                                             @Param("soon") Instant soon, @Param("since") Instant since);
}
