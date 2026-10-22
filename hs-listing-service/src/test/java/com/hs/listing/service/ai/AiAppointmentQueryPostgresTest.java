package com.hs.listing.service.ai;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.ai.AiAppointmentDtos.*;
import com.hs.listing.dto.ai.AiAppointmentQuery;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.AppointmentCancelledBy;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.DepositType;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.PaymentCycle;
import com.hs.listing.model.constant.PriceUnit;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ViewingAppointmentRepository;
import com.hs.listing.service.ViewingAppointmentService;
import com.hs.storage.config.StorageProperties;
import com.hs.listing.model.ListingAddress;
import com.hs.listing.repository.ListingAddressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Runs the new appointment queries against a real PostgreSQL (the SQL, joins, group-by and paging are what is
 * under test, not a mock). Opt-in: set HS_TEST_PG_URL (e.g. jdbc:postgresql://localhost:55432/hs_test) and
 * optionally HS_TEST_PG_USER / HS_TEST_PG_PASSWORD. The schema is created by Hibernate and rolled back per test,
 * so point it at a throwaway database only, never at a shared development database.
 */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}",
        "spring.datasource.username=${HS_TEST_PG_USER:postgres}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD:test}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"})
class AiAppointmentQueryPostgresTest {

    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = {ViewingAppointmentRepository.class, ListingRepository.class,
            ListingAddressRepository.class})
    @EnableJpaAuditing
    static class Config {}

    static final ZoneId VN = ViewingAppointmentService.VIETNAM_ZONE;
    // Wednesday 14 Oct 2026, 10:30 Vietnam time.
    static final Instant NOW = Instant.parse("2026-10-14T03:30:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    static final String ALICE = "alice", BOB = "bob", CAROL = "carol", DAN = "dan";
    static final String L1 = "11111111-1111-1111-1111-111111111111";
    static final String L2 = "22222222-2222-2222-2222-222222222222";
    static final String L3 = "33333333-3333-3333-3333-333333333333";
    static final String L4 = "44444444-4444-4444-4444-444444444444";

    @Autowired ViewingAppointmentRepository appointments;
    @Autowired ListingRepository listings;
    @Autowired ListingAddressRepository addresses;
    @PersistenceContext EntityManager em;

    AiAppointmentQueryService service;
    ViewingAppointmentService booking;

    @BeforeEach
    void setUp() {
        booking = new ViewingAppointmentService(appointments, listings, mock(ListingAddressRepository.class), mock(StorageProperties.class));
        service = new AiAppointmentQueryService(appointments, listings, booking, em, Clock.fixed(NOW, VN));
        Listing l1 = listing(L1, ALICE, "Phong tro Phu Loi", ListingStatus.PUBLISHED);
        Listing l2 = listing(L2, DAN, "Can ho Thu Duc", ListingStatus.PUBLISHED);
        Listing l3 = listing(L3, DAN, "Nha nguyen can", ListingStatus.PUBLISHED);
        Listing l4 = listing(L4, DAN, "Ban nhap", ListingStatus.DRAFT);
        address(L1, "12 Duong Cay Bang, Phuong Phu Loi, TP Ho Chi Minh");
        address(L2, "5 Duong So 9, Phuong Thu Duc");

        // Alice owns L1; Alice is a renter on L2/L3 (so she holds both roles).
        appt(1, l1, ALICE, BOB, TODAY, 8, AppointmentStatus.CONFIRMED, 2);          // finished, scheduler not run yet
        appt(2, l1, ALICE, CAROL, TODAY, 10, AppointmentStatus.CONFIRMED, 3);       // in progress now
        ViewingAppointment a3 = appt(3, l1, ALICE, "erin", TODAY, 14, AppointmentStatus.PENDING, 1);
        a3.setRescheduleRequested(true);
        a3.setProposedDate(TODAY.plusDays(1));
        a3.setProposedStartTime(LocalTime.of(9, 0));
        a3.setProposedEndTime(LocalTime.of(10, 0));
        a3.setProposedSlotType(ViewingSlot.MORNING);
        a3.setRescheduleReason("busy");
        appt(4, l1, ALICE, "frank", TODAY, 14, AppointmentStatus.PENDING, 4);       // same slot as #3
        appt(5, l1, ALICE, "gina", TODAY.plusDays(1), 9, AppointmentStatus.PENDING, 1);
        appt(6, l2, DAN, ALICE, TODAY, 16, AppointmentStatus.CONFIRMED, 2);
        appt(7, l3, DAN, ALICE, LocalDate.of(2026, 10, 18), 9, AppointmentStatus.PENDING, 1); // Sunday
        ViewingAppointment a8 = appt(8, l3, DAN, ALICE, LocalDate.of(2026, 10, 12), 9, AppointmentStatus.CANCELLED, 1);
        a8.setCancelledBy(AppointmentCancelledBy.OWNER);
        appt(9, l2, DAN, BOB, TODAY, 15, AppointmentStatus.PENDING, 1);             // not Alice's
        ViewingAppointment a10 = appt(10, l1, ALICE, "hank", TODAY, 15, AppointmentStatus.PENDING, 1);
        a10.setActive(false);                                                       // soft-deleted
        appt(11, l1, ALICE, "ivy", LocalDate.of(2026, 10, 20), 9, AppointmentStatus.CONFIRMED, 1);
        appt(12, l1, ALICE, "jon", LocalDate.of(2026, 11, 2), 9, AppointmentStatus.PENDING, 1);
        appt(13, l1, ALICE, "kim", LocalDate.of(2026, 12, 31), 20, AppointmentStatus.PENDING, 1);
        appt(14, l1, ALICE, "lee", LocalDate.of(2027, 1, 1), 8, AppointmentStatus.PENDING, 1);
        appointments.saveAll(List.of(a3, a8, a10));
        em.flush();
        em.clear();
    }

    // ---------------------------------------------------------------- scope and counting

    @Test
    void summaryTotalMatchesSearchTotalAndPagesDoNotChangeIt() {
        SummaryResponse summary = service.summarize(ALICE, q().build());
        SearchResponse page1 = service.search(ALICE, q().size("5").build());
        SearchResponse page3 = service.search(ALICE, q().size("5").page("3").build());
        assertEquals(12, summary.total());
        assertEquals(12, page1.page().totalElements());
        assertEquals(3, page1.page().totalPages());
        assertTrue(page1.page().hasNext());
        assertEquals(5, page1.items().size());
        assertEquals(2, page3.items().size());
        assertFalse(page3.page().hasNext());
        assertEquals(12, summary.byStatus().values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void bothRolesAreNotDoubleCountedAndSplitByRole() {
        SummaryResponse summary = service.summarize(ALICE, q().build());
        assertEquals(9, summary.byRole().get("OWNER"));
        assertEquals(3, summary.byRole().get("RENTER"));
        assertEquals(summary.total(), summary.byRole().values().stream().mapToLong(Long::longValue).sum());
        long distinct = service.search(ALICE, q().size("20").build()).items().stream()
                .map(Item::appointmentId).distinct().count();
        assertEquals(12, distinct);
    }

    @Test
    void ownerAndRenterScopesAreDisjointAndRenterNameIsOwnerOnly() {
        SearchResponse owner = service.search(ALICE, q().role("OWNER").size("20").build());
        SearchResponse renter = service.search(ALICE, q().role("RENTER").size("20").build());
        assertEquals(9, owner.page().totalElements());
        assertEquals(3, renter.page().totalElements());
        assertTrue(owner.items().stream().allMatch(i -> i.requesterRole().equals("OWNER") && i.renterName() != null));
        assertTrue(renter.items().stream().allMatch(i -> i.requesterRole().equals("RENTER") && i.renterName() == null));
    }

    @Test
    void strangerAndOtherUsersOnlySeeTheirOwnRows() {
        assertEquals(0, service.summarize("nobody", q().build()).total());
        assertEquals(0, service.search("nobody", q().build()).page().totalElements());
        SearchResponse carol = service.search(CAROL, q().build());
        assertEquals(1, carol.page().totalElements());
        assertEquals("RENTER", carol.items().get(0).requesterRole());
        // saying "I am the owner" changes nothing: the role filter only narrows within the caller's own rows
        assertEquals(0, service.search(CAROL, q().role("OWNER").build()).page().totalElements());
    }

    @Test
    void inactiveRowsAndOtherPeoplesRowsNeverAppear() {
        List<String> ids = service.search(ALICE, q().size("20").build()).items().stream().map(Item::appointmentId).toList();
        assertFalse(ids.contains(id(9)));
        assertFalse(ids.contains(id(10)));
    }

    // ---------------------------------------------------------------- dates and presets

    @Test
    void datePresetsResolveFromTheServerClockWithMondayWeeks() {
        assertEquals(Set.of(id(1), id(2), id(3), id(4), id(6)), ids(q().datePreset("TODAY")));
        assertEquals(Set.of(id(5)), ids(q().datePreset("TOMORROW")));
        assertEquals(Set.of(id(1), id(2), id(3), id(4), id(5), id(6), id(7), id(8)), ids(q().datePreset("THIS_WEEK")));
        assertEquals(Set.of(id(7)), ids(q().datePreset("THIS_WEEKEND")));
        assertEquals(Set.of(id(11)), ids(q().datePreset("NEXT_WEEK")));
        assertEquals(Set.of(id(1), id(2), id(3), id(4), id(5), id(6), id(7), id(8), id(11)), ids(q().datePreset("THIS_MONTH")));
        DateRange week = service.search(ALICE, q().datePreset("THIS_WEEK").build()).filters().dateRange();
        assertEquals(LocalDate.of(2026, 10, 12), week.from());
        assertEquals(LocalDate.of(2026, 10, 18), week.to());
        assertEquals("THIS_WEEK", week.preset());
    }

    @Test
    void proposedRescheduleTimeNeverCountsAsTheCurrentSchedule() {
        // #3 proposes tomorrow 09:00 but is still booked today 14:00
        assertFalse(ids(q().datePreset("TOMORROW")).contains(id(3)));
        assertTrue(ids(q().datePreset("TODAY")).contains(id(3)));
    }

    @Test
    void rangesAcrossMonthAndYearBoundariesAreInclusive() {
        assertEquals(Set.of(id(13), id(14)), ids(q().dateFrom("2026-12-30").dateTo("2027-01-02")));
        assertEquals(Set.of(id(13)), ids(q().dateFrom("2026-12-31").dateTo("2026-12-31")));
        assertEquals(Set.of(id(12)), ids(q().dateFrom("2026-11-01").dateTo("2026-11-30")));
    }

    @Test
    void startTimeWindowIsHalfOpen() {
        assertEquals(Set.of(id(2)), ids(q().datePreset("TODAY").timeFrom("09:00").timeTo("11:00")));
        assertEquals(Set.of(), ids(q().datePreset("TODAY").timeFrom("09:00").timeTo("10:00"))); // 10:00 excluded
        assertEquals(Set.of(id(2)), ids(q().datePreset("TODAY").timeFrom("10:00").timeTo("11:00")));
    }

    @Test
    void invalidQueriesFailLoudlyInsteadOfBeingTruncated() {
        assertInvalid(q().datePreset("TODAY").dateFrom("2026-10-14").dateTo("2026-10-14"));
        assertInvalid(q().dateFrom("2026-10-14"));
        assertInvalid(q().dateFrom("2026-10-20").dateTo("2026-10-14"));
        assertInvalid(q().dateFrom("2026-01-01").dateTo("2027-02-01")); // 397 days
        assertInvalid(q().dateFrom("2026-02-30").dateTo("2026-03-01"));
        assertInvalid(q().datePreset("LAST_YEAR"));
        assertInvalid(q().role("ADMIN"));
        assertInvalid(q().status("DELETED"));
        assertInvalid(q().timePhase("SOON"));
        assertInvalid(q().timeFrom("11:00").timeTo("09:00"));
        assertInvalid(q().size("21"));
        assertInvalid(q().size("0"));
        assertInvalid(q().page("0"));
        assertInvalid(q().sort("RANDOM"));
        assertInvalid(q().listingId("not-a-uuid"));
        assertInvalid(q().q("x".repeat(AiAppointmentQueryService.MAX_KEYWORD + 1)));
        assertInvalid(q().rescheduleRequested("maybe"));
    }

    // ---------------------------------------------------------------- phases and ordering

    @Test
    void temporalPhaseIsSeparateFromStoredStatusAndOldRowsAreNotUpcoming() {
        SearchResponse today = service.search(ALICE, q().datePreset("TODAY").size("20").build());
        Map<String, Item> byId = today.items().stream().collect(java.util.stream.Collectors.toMap(Item::appointmentId, i -> i));
        Item finished = byId.get(id(1));
        assertEquals("CONFIRMED", finished.status());
        assertEquals("PAST", finished.temporalPhase());
        assertTrue(finished.statusSyncPending());
        assertEquals("IN_PROGRESS", byId.get(id(2)).temporalPhase());
        assertEquals("UPCOMING", byId.get(id(3)).temporalPhase());
        assertFalse(byId.get(id(3)).statusSyncPending());

        SearchResponse upcoming = service.search(ALICE, q().datePreset("TODAY").status("PENDING", "CONFIRMED")
                .timePhase("UPCOMING", "IN_PROGRESS").size("20").build());
        List<String> order = upcoming.items().stream().map(Item::appointmentId).toList();
        assertEquals(List.of(id(2), id(3), id(4), id(6)), order); // #1 (finished) excluded; ties by id
        assertFalse(order.contains(id(1)));
    }

    @Test
    void summaryByPhaseAndNextUpcomingUseNowNotNewestRecord() {
        SummaryResponse today = service.summarize(ALICE, q().datePreset("TODAY").build());
        assertEquals(5, today.total());
        assertEquals(3, today.byTemporalPhase().get("UPCOMING"));
        assertEquals(1, today.byTemporalPhase().get("IN_PROGRESS"));
        assertEquals(1, today.byTemporalPhase().get("PAST"));
        assertEquals(id(3), today.nextUpcoming().appointmentId()); // 14:00, smaller id wins the tie; not #1 (past)
        // visitors are people expected on PENDING/CONFIRMED rows, clearly apart from the number of bookings
        assertEquals(12, today.expectedVisitorsTotal());
        assertEquals(5, today.appointmentsCountedForVisitors());
        assertNotEquals(today.total(), today.expectedVisitorsTotal());
    }

    @Test
    void sortsAreDeterministic() {
        List<String> asc = service.search(ALICE, q().sort("TIME_ASC").size("20").build()).items().stream().map(Item::appointmentId).toList();
        List<String> desc = service.search(ALICE, q().sort("TIME_DESC").size("20").build()).items().stream().map(Item::appointmentId).toList();
        assertEquals(asc.reversed().stream().filter(i -> !i.equals(id(3)) && !i.equals(id(4))).toList(),
                desc.stream().filter(i -> !i.equals(id(3)) && !i.equals(id(4))).toList());
        assertEquals(id(8), asc.get(0)); // 12 Oct is the earliest
        assertEquals(12, service.search(ALICE, q().sort("CREATED_DESC").size("20").build()).items().size());
    }

    // ---------------------------------------------------------------- other filters

    @Test
    void listingRescheduleStatusAndKeywordFilters() {
        assertEquals(9, service.search(ALICE, q().listingId(L1).size("20").build()).page().totalElements());
        assertEquals(Set.of(id(3)), ids(q().rescheduleRequested("true")));
        assertEquals(1, service.summarize(ALICE, q().role("OWNER").rescheduleRequested("true").build()).pendingRescheduleRequests());
        assertEquals(1, service.summarize(ALICE, q().build()).pendingRescheduleRequests());
        assertEquals(Set.of(id(8)), ids(q().status("CANCELLED")));
        assertEquals(Set.of(id(7), id(8)), ids(q().q("NGUYEN")));                 // listing title, case-insensitive
        assertEquals(Set.of(id(3), id(4), id(1), id(2), id(5), id(11), id(12), id(13), id(14)), ids(q().q("cay bang"))); // address
        assertEquals(Set.of(id(2)), ids(q().q("carol")));                         // renter name: owner rows only
        assertEquals(Set.of(), ids(q().role("RENTER").q("carol")));
        assertEquals(Set.of(), ids(q().q("%")));                                  // LIKE wildcards are escaped
        assertEquals(Set.of(), ids(q().q("_")));
    }

    // ---------------------------------------------------------------- detail and actions

    @Test
    void detailExposesOnlyWhatTheCallerMaySeeAndKeepsProposedApart() {
        Detail owner = service.detail(ALICE, id(3));
        assertEquals("OWNER", owner.requesterRole());
        assertEquals("PENDING", owner.status());
        assertEquals(LocalTime.of(14, 0), owner.currentSchedule().startTime());
        assertEquals(TODAY.plusDays(1), owner.proposedSchedule().date());
        assertEquals("erin", owner.renterName());
        assertTrue(owner.actions().available().containsAll(List.of("APPROVE", "REJECT", "APPROVE_RESCHEDULE", "REJECT_RESCHEDULE", "CANCEL")));
        assertEquals(List.of("STATUS_NOT_CONFIRMED"), owner.actions().blocked().get("COMPLETE"));
        assertTrue(owner.unavailable().containsKey("changeHistory"));

        Detail renter = service.detail(ALICE, id(6));
        assertEquals("RENTER", renter.requesterRole());
        assertNull(renter.renterName());
        assertNull(renter.proposedSchedule());
        assertEquals(List.of("REQUEST_RESCHEDULE", "CANCEL"), renter.actions().available());

        Detail cancelled = service.detail(ALICE, id(8));
        assertEquals("OWNER", cancelled.cancelledBy());
        assertNull(cancelled.cancelReason());
        assertTrue(cancelled.unavailable().containsKey("cancelReason"));
        assertEquals(List.of("STATUS_NOT_PENDING_OR_CONFIRMED"), cancelled.actions().blocked().get("CANCEL"));
    }

    @Test
    void detailOfMissingForeignOrInactiveRowsIsIndistinguishable() {
        for (String id : List.of(id(1) /* carol cannot see */, id(10), "99999999-9999-9999-9999-999999999999")) {
            AppException e = assertThrows(AppException.class, () -> service.detail(CAROL, id));
            assertEquals(ListingErrorCode.APPOINTMENT_NOT_FOUND.getCode(), e.getCode());
        }
        AppException inactive = assertThrows(AppException.class, () -> service.detail(ALICE, id(10)));
        assertEquals(ListingErrorCode.APPOINTMENT_NOT_FOUND.getCode(), inactive.getCode());
        AppException malformed = assertThrows(AppException.class, () -> service.detail(ALICE, "1' or '1'='1"));
        assertEquals(ListingErrorCode.APPOINTMENT_AI_QUERY_INVALID.getCode(), malformed.getCode());
        assertThrows(AppException.class, () -> service.summarize(" ", q().build())); // not signed in
    }

    // ---------------------------------------------------------------- availability

    @Test
    void availabilityCountsRequestsWithoutLeakingPeopleAndHidesUnpublishedListings() {
        LocalDate day = LocalDate.now(VN).plusDays(3);
        Listing l2 = listings.findById(L2).orElseThrow();
        l2.setViewingDays(Set.of(DayOfWeek.values()));
        l2.setViewingSlots(Set.of(ViewingSlot.MORNING));
        appointments.save(appt(21, l2, DAN, BOB, day, 8, AppointmentStatus.PENDING, 1));
        appointments.save(appt(22, l2, DAN, CAROL, day, 8, AppointmentStatus.CONFIRMED, 1));
        appointments.save(appt(23, l2, DAN, "erin", day, 9, AppointmentStatus.REJECTED, 1));
        em.flush();
        em.clear();

        Availability result = service.availability(ALICE, L2, day.toString());
        assertTrue(result.dayAccepted());
        AvailabilitySlot eight = result.slots().stream().filter(s -> s.startTime().equals(LocalTime.of(8, 0))).findFirst().orElseThrow();
        assertEquals(2, eight.bookingCount());                 // two requests share the slot ...
        assertEquals("CAN_REQUEST", eight.status());           // ... it is not "full"
        AvailabilitySlot nine = result.slots().stream().filter(s -> s.startTime().equals(LocalTime.of(9, 0))).findFirst().orElseThrow();
        assertEquals(0, nine.bookingCount());                  // REJECTED does not count
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("not the number of people")));
        assertNotNull(result.callerActiveBookingId());         // Alice has an active request (#6) on this listing

        Availability asOwner = service.availability(DAN, L2, day.toString());
        assertTrue(asOwner.callerIsOwner());
        assertTrue(asOwner.slots().stream().allMatch(s -> s.status().equals("OWNER_CANNOT_BOOK_OWN_LISTING")));

        AppException draft = assertThrows(AppException.class, () -> service.availability(ALICE, L4, day.toString()));
        assertEquals(ListingErrorCode.LISTING_NOT_FOUND.getCode(), draft.getCode());
        assertDoesNotThrow(() -> service.availability(DAN, L4, day.toString())); // its owner may look
        assertEquals(ListingErrorCode.APPOINTMENT_AI_QUERY_INVALID.getCode(),
                assertThrows(AppException.class, () -> service.availability(ALICE, L2, "2030-01-01")).getCode());
        assertEquals(ListingErrorCode.APPOINTMENT_AI_QUERY_INVALID.getCode(),
                assertThrows(AppException.class, () -> service.availability(ALICE, "nope", null)).getCode());
    }

    @Test
    void availabilityDayIsResolvedByTheServerClockNotByTheCaller() {
        // fixed "now" is Wednesday 2026-10-14
        assertEquals(TODAY, service.availability(ALICE, L2, null, "TODAY", null, null).date());
        assertEquals(TODAY.plusDays(1), service.availability(ALICE, L2, null, "TOMORROW", null, null).date());
        assertEquals(TODAY.plusDays(2), service.availability(ALICE, L2, null, "DAY_AFTER_TOMORROW", null, null).date());
        assertEquals(LocalDate.of(2026, 10, 17), service.availability(ALICE, L2, null, null, "SATURDAY", null).date());
        assertEquals(TODAY, service.availability(ALICE, L2, null, null, "WEDNESDAY", null).date());
        assertEquals(LocalDate.of(2026, 10, 12), service.availability(ALICE, L2, null, null, "MONDAY", "THIS").date());
        assertEquals(LocalDate.of(2026, 10, 19), service.availability(ALICE, L2, null, null, "MONDAY", "NEXT").date());
        int invalid = ListingErrorCode.APPOINTMENT_AI_QUERY_INVALID.getCode();
        for (String[] bad : new String[][]{{"2026-10-15", "TODAY", null, null}, {null, "TODAY", "MONDAY", null},
                {null, "NEXT_YEAR", null, null}, {null, null, "FUNDAY", null}, {null, null, null, "NEXT"},
                {null, null, "MONDAY", "LATER"}}) {
            assertEquals(invalid, assertThrows(AppException.class,
                    () -> service.availability(ALICE, L2, bad[0], bad[1], bad[2], bad[3])).getCode());
        }
    }

    // ---------------------------------------------------------------- read-only guarantee

    @Test
    void readingNeverChangesStatusesOrRowCounts() {
        long before = appointments.count();
        Map<String, AppointmentStatus> snapshot = statuses();
        service.summarize(ALICE, q().build());
        service.search(ALICE, q().datePreset("TODAY").build());
        service.detail(ALICE, id(1));
        service.availability(ALICE, L2, null);
        em.flush();
        em.clear();
        assertEquals(before, appointments.count());
        assertEquals(snapshot, statuses());
        // the finished CONFIRMED booking is still CONFIRMED: reading does not run the scheduler's sync
        assertEquals(AppointmentStatus.CONFIRMED, appointments.findById(id(1)).orElseThrow().getStatus());
    }

    @Test
    void realVietnamClockIsUsedNotTheContractSimulationClock() {
        System.setProperty("HOMESPACE_TIME_SIMULATED_AT", "2030-01-01T00:00:00Z");
        try {
            AiAppointmentQueryService real = new AiAppointmentQueryService(appointments, listings, booking, em);
            Meta meta = real.summarize(ALICE, q().build()).meta();
            assertEquals(LocalDate.now(VN), meta.today());
            assertEquals("Asia/Ho_Chi_Minh", meta.timezone());
        } finally {
            System.clearProperty("HOMESPACE_TIME_SIMULATED_AT");
        }
    }

    // ---------------------------------------------------------------- fixtures

    Map<String, AppointmentStatus> statuses() {
        return appointments.findAll().stream().collect(java.util.stream.Collectors.toMap(ViewingAppointment::getId, ViewingAppointment::getStatus));
    }

    static String id(int n) {
        return "aaaaaaaa-0000-0000-0000-%012d".formatted(n);
    }

    Set<String> ids(QueryBuilder b) {
        return service.search(ALICE, b.size("20").build()).items().stream().map(Item::appointmentId)
                .collect(java.util.stream.Collectors.toSet());
    }

    void assertInvalid(QueryBuilder b) {
        AppException e = assertThrows(AppException.class, () -> service.search(ALICE, b.build()));
        assertEquals(ListingErrorCode.APPOINTMENT_AI_QUERY_INVALID.getCode(), e.getCode());
        assertThrows(AppException.class, () -> service.summarize(ALICE, b.build())); // same rules for summary
    }

    Listing listing(String id, String ownerId, String title, ListingStatus status) {
        Listing l = Listing.builder().id(id).ownerId(ownerId).title(title).description("d")
                .category(ListingCategory.ROOM).status(status).availableFrom(TODAY).areaM2(BigDecimal.TEN)
                .priceAmount(BigDecimal.valueOf(1_000_000)).currency("VND").priceUnit(PriceUnit.MONTH)
                .depositType(DepositType.NONE).paymentCycle(PaymentCycle.MONTHLY).minimumLeaseMonths(1)
                .viewingDays(Set.of(DayOfWeek.values())).viewingSlots(Set.of(ViewingSlot.MORNING)).build();
        l.setActive(true);
        return listings.save(l);
    }

    @Test
    void vietmapSchemaHasIndependentUserListingAndBranchRowsAndNoLegacyTable() {
        ListingAddress listingAddress=addresses.findByListingIdAndActiveTrue(L1).orElseThrow();
        var user=new com.hs.user.model.User(); user.setId("address-user");user.setUsername("address-user");user.setEmail("address-user@example.test");em.persist(user);
        var profileAddress=new com.hs.user.model.Address();profileAddress.setUser(user);profileAddress.copyAddressFrom(listingAddress);em.persist(profileAddress);
        var branchAddress=new com.hs.listing.model.BranchAddress();branchAddress.copyAddressFrom(listingAddress);
        var branch=com.hs.listing.model.PropertyBranch.builder().id("address-branch").ownerId(ALICE).name("Branch")
                .category(com.hs.listing.model.constant.ListingCategory.ROOM).address(branchAddress).build();em.persist(branch);em.flush();
        assertNotEquals(profileAddress.getId(),listingAddress.getId());assertNotEquals(branchAddress.getId(),listingAddress.getId());
        profileAddress.setDisplay("Changed private address");em.flush();em.clear();
        assertNotEquals("Changed private address",addresses.findByListingIdAndActiveTrue(L1).orElseThrow().getDisplay());
        assertEquals(0L,((Number)em.createNativeQuery("select count(*) from information_schema.tables where table_schema='public' and table_name='addresses'").getSingleResult()).longValue());
        assertEquals(3L,((Number)em.createNativeQuery("select count(*) from information_schema.tables where table_schema='public' and table_name in ('user_addresses','listing_addresses','branch_addresses')").getSingleResult()).longValue());
        assertEquals(0L,((Number)em.createNativeQuery("select count(*) from information_schema.columns where table_schema='public' and table_name in ('user_addresses','listing_addresses','branch_addresses') and column_name in ('province_code','province_name','ward_code','ward_name','street_line','full_address')").getSingleResult()).longValue());
        var facets=em.createQuery("select distinct a.cityId,a.city from ListingAddress a, Listing l where a.listingId=l.id and a.active=true and l.active=true and l.status=com.hs.listing.model.constant.ListingStatus.PUBLISHED order by a.city",Object[].class).getResultList();
        assertEquals("12",facets.getFirst()[0]);
    }

    void address(String listingId, String full) {
        ListingAddress a = new ListingAddress();
        a.setListingId(listingId);
        a.setCityId("12");
        a.setCity("TP Ho Chi Minh");
        a.setWardId("18700");
        a.setWard("Phuong");
        a.setStreet("street");
        a.setDisplay(full);
        a.setRefId("vm:test"); a.setAddress("Phuong, TP"); a.setResolvedAt(java.time.Instant.now());
        a.setLatitude(java.math.BigDecimal.TEN); a.setLongitude(java.math.BigDecimal.valueOf(106));
        a.setActive(true);
        addresses.save(a);
    }

    ViewingAppointment appt(int n, Listing listing, String owner, String renter, LocalDate date, int hour,
                            AppointmentStatus status, int visitors) {
        ViewingAppointment a = ViewingAppointment.builder().id(id(n)).listing(listing).ownerId(owner).renterId(renter)
                .renterName(renter).renterPhone("0000000000").visitorCount(visitors).appointmentDate(date)
                .startTime(LocalTime.of(hour, 0)).endTime(LocalTime.of(hour + 1, 0)).slotType(ViewingSlot.MORNING)
                .status(status).rescheduleRequested(false).build();
        a.setActive(true);
        return appointments.save(a);
    }

    static QueryBuilder q() {
        return new QueryBuilder();
    }

    static final class QueryBuilder {
        String role, datePreset, dateFrom, dateTo, timeFrom, timeTo, listingId, reschedule, q, sort, page, size;
        List<String> status, timePhase;

        QueryBuilder role(String v) { role = v; return this; }
        QueryBuilder status(String... v) { status = List.of(v); return this; }
        QueryBuilder timePhase(String... v) { timePhase = List.of(v); return this; }
        QueryBuilder datePreset(String v) { datePreset = v; return this; }
        QueryBuilder dateFrom(String v) { dateFrom = v; return this; }
        QueryBuilder dateTo(String v) { dateTo = v; return this; }
        QueryBuilder timeFrom(String v) { timeFrom = v; return this; }
        QueryBuilder timeTo(String v) { timeTo = v; return this; }
        QueryBuilder listingId(String v) { listingId = v; return this; }
        QueryBuilder rescheduleRequested(String v) { reschedule = v; return this; }
        QueryBuilder q(String v) { q = v; return this; }
        QueryBuilder sort(String v) { sort = v; return this; }
        QueryBuilder page(String v) { page = v; return this; }
        QueryBuilder size(String v) { size = v; return this; }

        AiAppointmentQuery build() {
            return new AiAppointmentQuery(role, status, datePreset, dateFrom, dateTo, timeFrom, timeTo, listingId,
                    reschedule, timePhase, q, sort, page, size);
        }
    }
}
