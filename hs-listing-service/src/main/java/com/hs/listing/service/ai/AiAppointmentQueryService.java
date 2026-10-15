package com.hs.listing.service.ai;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.ai.AiAppointmentDtos.*;
import com.hs.listing.dto.ai.AiAppointmentQuery;
import com.hs.listing.dto.response.ListingAvailabilityResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ViewingAppointmentRepository;
import com.hs.listing.service.ViewingAppointmentService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only appointment queries for the AI assistant.
 *
 * <ul>
 *   <li>Identity is always the signed-in caller; there is no user parameter. Summary, search and detail share one
 *       permission scope (the caller as owner and/or renter), so counts can never exceed what search returns.</li>
 *   <li>Time is the real Asia/Ho_Chi_Minh clock (as in the booking flow), taken once per request. It is not the
 *       contract billing clock and is not affected by {@code HOMESPACE_TIME_SIMULATED_AT}.</li>
 *   <li>The stored status is never changed here; {@code temporalPhase} (PAST / IN_PROGRESS / UPCOMING) is derived
 *       from the schedule and kept apart. {@code statusSyncPending} is true while the scheduler has not yet moved a
 *       finished PENDING/CONFIRMED appointment on.</li>
 *   <li>A proposed reschedule is shown as {@code proposedSchedule} and never replaces the current schedule.</li>
 *   <li>Nothing is saved or flushed: every method is read-only.</li>
 * </ul>
 */
@Service
public class AiAppointmentQueryService {
    public static final int MAX_RANGE_DAYS = 366;
    public static final int MAX_PAGE_SIZE = 20;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE = 1000;
    public static final int MAX_KEYWORD = 60;
    private static final int MAX_NOTE = 500;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<AppointmentStatus> ACTIVE = EnumSet.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED);
    private static final Set<String> ROLES = Set.of("OWNER", "RENTER", "BOTH");
    private static final Set<String> PHASES = Set.of("PAST", "IN_PROGRESS", "UPCOMING");
    private static final Set<String> SORTS = Set.of("TIME_ASC", "TIME_DESC", "CREATED_DESC");
    private static final Set<String> PRESETS = Set.of(
            "TODAY", "TOMORROW", "THIS_WEEK", "NEXT_WEEK", "THIS_WEEKEND", "THIS_MONTH");

    private final ViewingAppointmentRepository appointments;
    private final ListingRepository listings;
    private final ViewingAppointmentService availabilityService;
    private final EntityManager em;
    private final Clock clock;

    @Autowired
    public AiAppointmentQueryService(ViewingAppointmentRepository appointments, ListingRepository listings,
                                     ViewingAppointmentService availabilityService, EntityManager em) {
        this(appointments, listings, availabilityService, em, Clock.system(ViewingAppointmentService.VIETNAM_ZONE));
    }

    /** Test seam: the clock is injectable, the default stays the real Vietnam clock of the booking flow. */
    public AiAppointmentQueryService(ViewingAppointmentRepository appointments, ListingRepository listings,
                                     ViewingAppointmentService availabilityService, EntityManager em, Clock clock) {
        this.appointments = appointments;
        this.listings = listings;
        this.availabilityService = availabilityService;
        this.em = em;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ public API

    @Transactional(readOnly = true)
    public SummaryResponse summarize(String userId, AiAppointmentQuery query) {
        requireUser(userId);
        Scope s = scope(userId, query);
        long total = count(s, null);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (AppointmentStatus st : AppointmentStatus.values()) byStatus.put(st.name(), 0L);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> grouped = cb.createTupleQuery();
        Root<ViewingAppointment> root = grouped.from(ViewingAppointment.class);
        Join<ViewingAppointment, Listing> listing = root.join("listing", JoinType.INNER);
        grouped.multiselect(root.get("status"), cb.count(root))
                .where(predicates(cb, root, listing, grouped, s, null).toArray(Predicate[]::new))
                .groupBy(root.get("status"));
        for (Tuple row : em.createQuery(grouped).getResultList()) {
            byStatus.put(((AppointmentStatus) row.get(0)).name(), (Long) row.get(1));
        }

        Map<String, Long> byRole = new LinkedHashMap<>();
        byRole.put("OWNER", count(s, (b, r) -> b.equal(r.get("ownerId"), userId)));
        byRole.put("RENTER", count(s, (b, r) -> b.and(b.equal(r.get("renterId"), userId),
                b.notEqual(r.get("ownerId"), userId))));

        Map<String, Long> byPhase = new LinkedHashMap<>();
        for (String phase : List.of("UPCOMING", "IN_PROGRESS", "PAST")) {
            byPhase.put(phase, count(s, (b, r) -> phase(b, r, s, phase)));
        }
        long pendingReschedule = count(s, (b, r) -> b.isTrue(r.get("rescheduleRequested")));

        CriteriaQuery<Long> visitors = cb.createQuery(Long.class);
        Root<ViewingAppointment> vRoot = visitors.from(ViewingAppointment.class);
        Join<ViewingAppointment, Listing> vListing = vRoot.join("listing", JoinType.INNER);
        List<Predicate> vPredicates = predicates(cb, vRoot, vListing, visitors, s, null);
        vPredicates.add(vRoot.get("status").in(ACTIVE));
        visitors.select(cb.sumAsLong(vRoot.<Integer>get("visitorCount"))).where(vPredicates.toArray(Predicate[]::new));
        Long visitorSum = em.createQuery(visitors).getSingleResult();
        long activeCount = count(s, (b, r) -> r.get("status").in(ACTIVE));

        Item next = null;
        Page<ViewingAppointment> upcoming = appointments.findAll(spec(s, (b, r) -> b.and(
                r.get("status").in(ACTIVE), phase(b, r, s, "UPCOMING"))),
                PageRequest.of(0, 1, sort("TIME_ASC")));
        if (!upcoming.isEmpty()) {
            ViewingAppointment first = upcoming.getContent().get(0);
            next = toItem(first, userId, addresses(List.of(first.getListing().getId())), s);
        }

        List<String> notes = new ArrayList<>();
        notes.add("total is the number of appointments (bookings), not people and not unique visitors.");
        notes.add("expectedVisitorsTotal adds visitorCount only over PENDING/CONFIRMED appointments in scope; it is "
                + "an expectation from the request, not attendance, and not remaining capacity of a time slot.");
        notes.add("Several appointments (from several renters) can share one time slot.");
        return new SummaryResponse(meta(s), filters(s), total, byStatus, byRole, byPhase, pendingReschedule,
                visitorSum == null ? 0L : visitorSum, activeCount, next, notes);
    }

    @Transactional(readOnly = true)
    public SearchResponse search(String userId, AiAppointmentQuery query) {
        requireUser(userId);
        Scope s = scope(userId, query);
        PageRequest pageable = PageRequest.of(s.page - 1, s.size, sort(s.sort));
        Page<ViewingAppointment> result = appointments.findAll(spec(s, null), pageable);
        Map<String, String> addressByListing = addresses(result.getContent().stream()
                .map(a -> a.getListing().getId()).distinct().toList());
        List<Item> items = result.getContent().stream().map(a -> toItem(a, userId, addressByListing, s)).toList();
        PageInfo page = new PageInfo(s.page, s.size, result.getTotalElements(), result.getTotalPages(), result.hasNext());
        return new SearchResponse(meta(s), filters(s), page, items);
    }

    @Transactional(readOnly = true)
    public Detail detail(String userId, String appointmentId) {
        requireUser(userId);
        if (appointmentId == null || !UUID_TEXT.matcher(appointmentId).matches()) throw invalid();
        Instant now = clock.instant();
        ViewingAppointment a = appointments.findByIdWithListing(appointmentId)
                .filter(v -> userId.equals(v.getOwnerId()) || userId.equals(v.getRenterId()))
                .orElseThrow(AiAppointmentQueryService::notFound); // missing, inactive and foreign look identical
        boolean owner = userId.equals(a.getOwnerId());
        Scope s = scopeAt(userId, now.atZone(ViewingAppointmentService.VIETNAM_ZONE));
        String phase = phase(a.getAppointmentDate(), a.getStartTime(), a.getEndTime(), s);
        boolean pendingSync = ACTIVE.contains(a.getStatus()) && "PAST".equals(phase);
        boolean reschedule = Boolean.TRUE.equals(a.getRescheduleRequested());
        Listing l = a.getListing();
        Map<String, String> address = addresses(List.of(l.getId()));
        Map<String, String> unavailable = new LinkedHashMap<>();
        unavailable.put("changeHistory", "The system stores only the latest state; earlier reschedule requests and their "
                + "outcomes are not kept.");
        unavailable.put("contact", "Phone and email are not provided to the assistant.");
        if (a.getRejectReason() == null) unavailable.put("rejectReason", "No reject reason is stored.");
        if (a.getStatus() == AppointmentStatus.CANCELLED && a.getCancelReason() == null) {
            unavailable.put("cancelReason", "No cancel reason is stored.");
        }
        if (a.getStatus() == AppointmentStatus.EXPIRED) {
            unavailable.put("expiredCause", "EXPIRED means the schedule passed without a decision; no further reason is stored.");
        }
        String completionNote = a.getStatus() != AppointmentStatus.COMPLETED ? null
                : "COMPLETED can be set by the owner or automatically after the end time; it does not prove the "
                + "renter actually attended.";
        Notes notes = new Notes(cut(a.getRenterNote()), cut(a.getOwnerNote()));
        return new Detail(meta(s), a.getId(), owner ? "OWNER" : "RENTER",
                new ListingRef(l.getId(), l.getTitle(), address.get(l.getId())),
                new Schedule(a.getAppointmentDate(), a.getStartTime(), a.getEndTime(), name(a.getSlotType())),
                reschedule ? proposed(a) : null, reschedule ? cut(a.getRescheduleReason()) : null,
                a.getStatus().name(), phase, pendingSync, reschedule, a.getVisitorCount(),
                owner ? a.getRenterName() : null, notes, cut(a.getRejectReason()), name(a.getCancelledBy()),
                cut(a.getCancelReason()), a.getCreatedAt(), a.getUpdatedAt(), a.getCompletedAt(), completionNote,
                AppointmentActionRules.forRole(owner, a.getStatus(), reschedule, l.getStatus()), unavailable);
    }

    public Availability availability(String userId, String listingId, String date) {
        return availability(userId, listingId, date, null, null, null);
    }

    /**
     * Availability of one listing on one day. The day is resolved here from the server clock (the model never
     * supplies "today"): an explicit {@code date}, or {@code datePreset} (TODAY, TOMORROW, DAY_AFTER_TOMORROW), or a
     * {@code weekday} (next occurrence on or after today; {@code week} THIS/NEXT pins it to the current/next
     * Monday-Sunday week). At most one of the three; none means today.
     */
    @Transactional(readOnly = true)
    public Availability availability(String userId, String listingId, String date, String datePreset, String weekday,
                                     String week) {
        requireUser(userId);
        if (listingId == null || !UUID_TEXT.matcher(listingId).matches()) throw invalid();
        ZonedDateTime now = clock.instant().atZone(ViewingAppointmentService.VIETNAM_ZONE);
        List<String> notes = new ArrayList<>();
        LocalDate day = resolveDay(now.toLocalDate(), date, datePreset, weekday, week, notes);
        Listing listing = listings.findByIdAndActiveTrue(listingId)
                .filter(l -> l.getStatus() == ListingStatus.PUBLISHED || userId.equals(l.getOwnerId()))
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND)); // unpublished == missing
        boolean owner = userId.equals(listing.getOwnerId());
        ListingAvailabilityResponse raw = availabilityService.getAvailability(listingId, day, userId);
        List<AvailabilitySlot> slots = raw.slots().stream().map(slot -> new AvailabilitySlot(
                slot.startTime(), slot.endTime(), slot.slotType().name(), slotState(slot.status(), owner),
                slot.bookingCount())).toList();
        notes.add("CAN_REQUEST means the owner's schedule and the booking-notice rule allow sending a request; it is "
                + "not a guarantee the owner will accept, and identity verification (KYC) is checked only when the request is submitted.");
        notes.add("bookingCount is the number of appointment requests (PENDING or CONFIRMED) on that slot. It is not the "
                + "number of people, not remaining capacity, and a busy slot is not 'full'.");
        notes.add("Slots starting less than one hour from now are NOT_AVAILABLE for today; past dates have no slots.");
        if (owner) notes.add("The caller owns this listing and cannot book a viewing of it.");
        if (raw.hasExistingActiveBooking()) {
            notes.add("The caller already has an active request for this listing; only one PENDING/CONFIRMED request per "
                    + "listing is allowed.");
        }
        Map<String, String> address = addresses(List.of(listingId));
        return new Availability(meta(scopeAt(userId, now)),
                new ListingRef(listingId, listing.getTitle(), address.get(listingId)), raw.date(),
                raw.dayOfWeek().name(), raw.isDayAvailable(),
                raw.allowedViewingDays().stream().map(DayOfWeek::name).toList(),
                raw.allowedViewingSlots().stream().map(Enum::name).toList(), slots, owner,
                raw.existingBookingId(), notes);
    }

    private static LocalDate resolveDay(LocalDate today, String date, String preset, String weekday, String week,
                                        List<String> notes) {
        int given = (notBlank(date) ? 1 : 0) + (notBlank(preset) ? 1 : 0) + (notBlank(weekday) ? 1 : 0);
        if (given > 1 || (notBlank(week) && !notBlank(weekday))) throw invalid(); // never guess between two days
        LocalDate day = null;
        if (notBlank(date)) {
            day = parseDate(date);
        } else if (notBlank(preset)) {
            String key = preset.trim().toUpperCase(Locale.ROOT);
            day = switch (key) {
                case "TODAY" -> today;
                case "TOMORROW" -> today.plusDays(1);
                case "DAY_AFTER_TOMORROW" -> today.plusDays(2);
                default -> throw invalid();
            };
            notes.add("Day resolved from " + key + " on the server clock.");
        } else if (notBlank(weekday)) {
            DayOfWeek dow;
            try {
                dow = DayOfWeek.valueOf(weekday.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            String scope = notBlank(week) ? week.trim().toUpperCase(Locale.ROOT) : "";
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            day = switch (scope) {
                case "" -> today.with(TemporalAdjusters.nextOrSame(dow));
                case "THIS" -> monday.with(TemporalAdjusters.nextOrSame(dow));
                case "NEXT" -> monday.plusDays(7).with(TemporalAdjusters.nextOrSame(dow));
                default -> throw invalid();
            };
            notes.add("Day resolved from " + dow + (scope.isEmpty() ? " (next occurrence on or after today)"
                    : " in the " + scope + " week (Monday-Sunday)") + " on the server clock.");
        }
        if (day != null && Math.abs(ChronoUnit.DAYS.between(today, day)) > MAX_RANGE_DAYS) throw invalid();
        return day;
    }

    // ------------------------------------------------------------------ query scope

    private record Scope(String userId, String role, Set<AppointmentStatus> statuses, LocalDate from, LocalDate to,
                         String preset, LocalTime timeFrom, LocalTime timeTo, String listingId,
                         Boolean reschedule, Set<String> phases, String keyword, String sort, int page, int size,
                         ZonedDateTime now) {
        LocalDate today() { return now.toLocalDate(); }
        LocalTime time() { return now.toLocalTime().truncatedTo(ChronoUnit.SECONDS); }
    }

    private Scope scopeAt(String userId, ZonedDateTime now) {
        return new Scope(userId, "BOTH", Set.of(), null, null, null, null, null, null, null, Set.of(), null,
                "TIME_ASC", 1, 1, now);
    }

    private Scope scope(String userId, AiAppointmentQuery q) {
        AiAppointmentQuery query = q == null ? new AiAppointmentQuery(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null) : q;
        ZonedDateTime now = clock.instant().atZone(ViewingAppointmentService.VIETNAM_ZONE); // one reference per request
        String role = query.role() == null || query.role().isBlank() ? "BOTH" : query.role().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw invalid();

        Set<AppointmentStatus> statuses = new LinkedHashSet<>();
        if (query.status() != null) {
            for (String raw : query.status()) {
                for (String part : String.valueOf(raw).split(",")) {
                    if (part.isBlank()) continue;
                    try {
                        statuses.add(AppointmentStatus.valueOf(part.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw invalid();
                    }
                }
            }
        }
        Set<String> phases = new LinkedHashSet<>();
        if (query.timePhase() != null) {
            for (String raw : query.timePhase()) {
                for (String part : String.valueOf(raw).split(",")) {
                    if (part.isBlank()) continue;
                    String phase = part.trim().toUpperCase(Locale.ROOT);
                    if (!PHASES.contains(phase)) throw invalid();
                    phases.add(phase);
                }
            }
        }

        LocalDate from = null;
        LocalDate to = null;
        String preset = null;
        boolean hasPreset = query.datePreset() != null && !query.datePreset().isBlank();
        boolean hasExplicit = notBlank(query.dateFrom()) || notBlank(query.dateTo());
        if (hasPreset && hasExplicit) throw invalid(); // never silently prefer one of two conflicting ranges
        if (hasPreset) {
            preset = query.datePreset().trim().toUpperCase(Locale.ROOT);
            if (!PRESETS.contains(preset)) throw invalid();
            LocalDate today = now.toLocalDate();
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            switch (preset) {
                case "TODAY" -> { from = today; to = today; }
                case "TOMORROW" -> { from = today.plusDays(1); to = from; }
                case "THIS_WEEK" -> { from = monday; to = monday.plusDays(6); }
                case "NEXT_WEEK" -> { from = monday.plusDays(7); to = monday.plusDays(13); }
                case "THIS_WEEKEND" -> { from = monday.plusDays(5); to = monday.plusDays(6); }
                default -> { from = today.withDayOfMonth(1); to = today.with(TemporalAdjusters.lastDayOfMonth()); }
            }
        } else if (hasExplicit) {
            if (!notBlank(query.dateFrom()) || !notBlank(query.dateTo())) throw invalid();
            from = parseDate(query.dateFrom());
            to = parseDate(query.dateTo());
            if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) throw invalid();
        }

        LocalTime timeFrom = notBlank(query.timeFrom()) ? parseTime(query.timeFrom()) : null;
        LocalTime timeTo = notBlank(query.timeTo()) ? parseTime(query.timeTo()) : null;
        if (timeFrom != null && timeTo != null && !timeFrom.isBefore(timeTo)) throw invalid();

        String listingId = null;
        if (notBlank(query.listingId())) {
            if (!UUID_TEXT.matcher(query.listingId().trim()).matches()) throw invalid();
            listingId = query.listingId().trim();
        }
        Boolean reschedule = null;
        if (notBlank(query.rescheduleRequested())) {
            String v = query.rescheduleRequested().trim().toLowerCase(Locale.ROOT);
            if (!v.equals("true") && !v.equals("false")) throw invalid();
            reschedule = Boolean.valueOf(v);
        }
        String keyword = null;
        if (query.q() != null && !query.q().isBlank()) {
            keyword = query.q().trim().toLowerCase(Locale.ROOT);
            if (keyword.length() > MAX_KEYWORD) throw invalid();
        }
        String sort = notBlank(query.sort()) ? query.sort().trim().toUpperCase(Locale.ROOT)
                : (!phases.isEmpty() || from != null ? "TIME_ASC" : "TIME_DESC");
        if (!SORTS.contains(sort)) throw invalid();
        int page = notBlank(query.page()) ? parseInt(query.page(), 1, MAX_PAGE) : 1;
        int size = notBlank(query.size()) ? parseInt(query.size(), 1, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        return new Scope(userId, role, statuses, from, to, preset, timeFrom, timeTo, listingId, reschedule, phases,
                keyword, sort, page, size, now);
    }

    @FunctionalInterface
    private interface Extra {
        Predicate apply(CriteriaBuilder cb, Root<ViewingAppointment> root);
    }

    private Specification<ViewingAppointment> spec(Scope s, Extra extra) {
        return (root, query, cb) -> {
            Join<ViewingAppointment, Listing> listing;
            if (query.getResultType() == ViewingAppointment.class) {
                listing = castJoin(root.fetch("listing", JoinType.INNER)); // one join, no N+1 for the page
            } else {
                listing = root.join("listing", JoinType.INNER); // count query for the page total
            }
            return cb.and(predicates(cb, root, listing, query, s, extra).toArray(Predicate[]::new));
        };
    }

    @SuppressWarnings("unchecked")
    private static Join<ViewingAppointment, Listing> castJoin(Object fetch) {
        return (Join<ViewingAppointment, Listing>) fetch;
    }

    /** Single definition of the permission scope and filters shared by summary, search and the page count. */
    private List<Predicate> predicates(CriteriaBuilder cb, Root<ViewingAppointment> root,
                                       Join<ViewingAppointment, Listing> listing, CriteriaQuery<?> query, Scope s,
                                       Extra extra) {
        List<Predicate> list = new ArrayList<>();
        list.add(cb.isTrue(root.get("active")));
        switch (s.role) {
            case "OWNER" -> list.add(cb.equal(root.get("ownerId"), s.userId));
            case "RENTER" -> list.add(cb.equal(root.get("renterId"), s.userId));
            default -> list.add(cb.or(cb.equal(root.get("ownerId"), s.userId), cb.equal(root.get("renterId"), s.userId)));
        }
        if (!s.statuses.isEmpty()) list.add(root.get("status").in(s.statuses));
        Path date = new Path(root);
        if (s.from != null) {
            list.add(cb.greaterThanOrEqualTo(date.date(), s.from));
            list.add(cb.lessThanOrEqualTo(date.date(), s.to));
        }
        if (s.timeFrom != null) list.add(cb.greaterThanOrEqualTo(date.start(), s.timeFrom));
        if (s.timeTo != null) list.add(cb.lessThan(date.start(), s.timeTo));
        if (s.listingId != null) list.add(cb.equal(listing.get("id"), s.listingId));
        if (s.reschedule != null) list.add(cb.equal(root.get("rescheduleRequested"), s.reschedule));
        if (!s.phases.isEmpty()) {
            List<Predicate> any = new ArrayList<>();
            for (String phase : s.phases) any.add(phase(cb, root, s, phase));
            list.add(cb.or(any.toArray(Predicate[]::new)));
        }
        if (s.keyword != null) list.add(keyword(cb, root, listing, query, s));
        if (extra != null) {
            Predicate p = extra.apply(cb, root);
            if (p != null) list.add(p);
        }
        return list;
    }

    /** Small typed accessors so the criteria code stays readable. */
    private record Path(Root<ViewingAppointment> root) {
        jakarta.persistence.criteria.Path<LocalDate> date() { return root.get("appointmentDate"); }
        jakarta.persistence.criteria.Path<LocalTime> start() { return root.get("startTime"); }
        jakarta.persistence.criteria.Path<LocalTime> end() { return root.get("endTime"); }
    }

    private Predicate keyword(CriteriaBuilder cb, Root<ViewingAppointment> root,
                              Join<ViewingAppointment, Listing> listing, CriteriaQuery<?> query, Scope s) {
        String pattern = "%" + s.keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Predicate> any = new ArrayList<>();
        any.add(cb.like(cb.lower(listing.get("title")), pattern, '\\'));
        // Renter names are matchable only on rows where the caller is the owner.
        any.add(cb.and(cb.equal(root.get("ownerId"), s.userId), cb.like(cb.lower(root.get("renterName")), pattern, '\\')));
        Subquery<Long> sub = query.subquery(Long.class);
        Root<com.hs.user.model.Address> address = sub.from(com.hs.user.model.Address.class);
        sub.select(cb.literal(1L)).where(cb.equal(address.get("listingId"), listing.get("id")),
                cb.isTrue(address.get("active")), cb.like(cb.lower(address.get("fullAddress")), pattern, '\\'));
        any.add(cb.exists(sub));
        return cb.or(any.toArray(Predicate[]::new));
    }

    private Predicate phase(CriteriaBuilder cb, Root<ViewingAppointment> root, Scope s, String phase) {
        Path p = new Path(root);
        LocalDate today = s.today();
        LocalTime now = s.time();
        return switch (phase) {
            case "UPCOMING" -> cb.or(cb.greaterThan(p.date(), today),
                    cb.and(cb.equal(p.date(), today), cb.greaterThan(p.start(), now)));
            case "IN_PROGRESS" -> cb.and(cb.equal(p.date(), today), cb.lessThanOrEqualTo(p.start(), now),
                    cb.greaterThan(p.end(), now));
            default -> cb.or(cb.lessThan(p.date(), today),
                    cb.and(cb.equal(p.date(), today), cb.lessThanOrEqualTo(p.end(), now)));
        };
    }

    private static String phase(LocalDate date, LocalTime start, LocalTime end, Scope s) {
        LocalDate today = s.today();
        LocalTime now = s.time();
        if (date.isAfter(today) || (date.equals(today) && start.isAfter(now))) return "UPCOMING";
        if (date.equals(today) && !start.isAfter(now) && end.isAfter(now)) return "IN_PROGRESS";
        return "PAST";
    }

    private long count(Scope s, Extra extra) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<ViewingAppointment> root = q.from(ViewingAppointment.class);
        Join<ViewingAppointment, Listing> listing = root.join("listing", JoinType.INNER);
        q.select(cb.count(root)).where(predicates(cb, root, listing, q, s, extra).toArray(Predicate[]::new));
        return em.createQuery(q).getSingleResult();
    }

    private static Sort sort(String name) {
        return switch (name) {
            case "TIME_DESC" -> Sort.by(Sort.Order.desc("appointmentDate"), Sort.Order.desc("startTime"), Sort.Order.asc("id"));
            case "CREATED_DESC" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
            default -> Sort.by(Sort.Order.asc("appointmentDate"), Sort.Order.asc("startTime"), Sort.Order.asc("id"));
        };
    }

    // ------------------------------------------------------------------ mapping

    private Item toItem(ViewingAppointment a, String userId, Map<String, String> addresses, Scope s) {
        boolean owner = userId.equals(a.getOwnerId());
        String phase = phase(a.getAppointmentDate(), a.getStartTime(), a.getEndTime(), s);
        boolean reschedule = Boolean.TRUE.equals(a.getRescheduleRequested());
        Listing l = a.getListing();
        return new Item(a.getId(), owner ? "OWNER" : "RENTER",
                new ListingRef(l.getId(), l.getTitle(), addresses.get(l.getId())),
                new Schedule(a.getAppointmentDate(), a.getStartTime(), a.getEndTime(), name(a.getSlotType())),
                reschedule ? proposed(a) : null, a.getStatus().name(), phase,
                ACTIVE.contains(a.getStatus()) && "PAST".equals(phase), reschedule, a.getVisitorCount(),
                owner ? a.getRenterName() : null, a.getCreatedAt());
    }

    private static Schedule proposed(ViewingAppointment a) {
        if (a.getProposedDate() == null) return null; // flagged but no proposal stored: say nothing rather than invent
        return new Schedule(a.getProposedDate(), a.getProposedStartTime(), a.getProposedEndTime(),
                name(a.getProposedSlotType()));
    }

    private Map<String, String> addresses(Collection<String> listingIds) {
        if (listingIds.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        em.createQuery("select a.listingId, a.fullAddress from Address a where a.listingId in :ids and a.active = true",
                        Object[].class)
                .setParameter("ids", listingIds).getResultList()
                .forEach(row -> out.put((String) row[0], (String) row[1]));
        return out;
    }

    private Meta meta(Scope s) {
        return new Meta(clock.instant(), s.now.toInstant(), ViewingAppointmentService.VIETNAM_ZONE.getId(),
                s.today(), s.time(), "REAL_TIME_ASIA_HO_CHI_MINH",
                "Weeks run Monday to Sunday; THIS_WEEKEND is Saturday-Sunday of the current week.",
                "UPCOMING: starts after now; IN_PROGRESS: started and not ended; PAST: ended (end time <= now).");
    }

    private Filters filters(Scope s) {
        return new Filters(s.role, s.statuses.stream().map(Enum::name).toList(),
                s.from == null ? null : new DateRange(s.from, s.to, s.preset), s.timeFrom, s.timeTo, s.listingId,
                s.reschedule, List.copyOf(s.phases), s.keyword, s.sort);
    }

    private static String slotState(String raw, boolean owner) {
        if (owner && "AVAILABLE".equals(raw)) return "OWNER_CANNOT_BOOK_OWN_LISTING";
        return switch (raw) {
            case "AVAILABLE" -> "CAN_REQUEST";
            case "PENDING_YOU" -> "YOUR_REQUEST_PENDING";
            case "CONFIRMED_YOU" -> "YOUR_BOOKING_CONFIRMED";
            default -> "NOT_AVAILABLE";
        };
    }

    // ------------------------------------------------------------------ helpers

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String cut(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.length() > MAX_NOTE ? text.substring(0, MAX_NOTE) : text;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value.trim());
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static int parseInt(String value, int low, int high) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < low || n > high) throw invalid();
            return n;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static AppException invalid() {
        return new AppException(ListingErrorCode.APPOINTMENT_AI_QUERY_INVALID);
    }

    private static AppException notFound() {
        return new AppException(ListingErrorCode.APPOINTMENT_NOT_FOUND);
    }
}
