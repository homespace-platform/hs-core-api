package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.dto.request.CreateContractDraftRequest;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.notification.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.*;
import com.hs.contract.service.impl.ContractServiceImpl;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.SaveBranchLayoutRequest;
import com.hs.listing.dto.request.SaveBranchLayoutRequest.*;
import com.hs.listing.dto.response.BranchLayoutResponse;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.DepositLedgerService;
import com.hs.payment.service.PaymentRequestService;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Hibernate owns this disposable database. Real repository, service and transaction boundaries. */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL",
        matches = "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/hs_branch_associations_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}",
        "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.hs.contract.service.BranchAssociationsPostgresTest$SqlTrace",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({BranchAssignmentService.class, BranchAssignmentGuard.class, BranchContractAutoLink.class,
        BranchRoomLinkService.class, BranchLayoutService.class, PropertyBranchService.class,
        ContractServiceImpl.class, ContractLifecycleService.class})
class BranchAssociationsPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {ContractRepository.class, RentalRequestRepository.class,
            PaymentRequestRepository.class, StorageObjectRepository.class})
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean BillingTime billingTime(Environment env) { return new BillingTime("2026-11-27T10:00:00", env); }
    }

    public static class SqlTrace implements StatementInspector {
        static final ThreadLocal<List<String>> statements = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            var trace = statements.get();
            if (trace != null) trace.add(sql);
            return sql;
        }
    }

    private static final String OWNER = "owner-a";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final BigDecimal RENT = new BigDecimal("3000000");

    @Autowired BranchAssignmentService service;
    @Autowired BranchContractAutoLink autoLink;
    @Autowired BranchRoomLinkService roomLinks;
    @Autowired BranchLayoutService layouts;
    @Autowired PropertyBranchService propertyBranches;
    @Autowired ContractServiceImpl creation;
    @Autowired ContractLifecycleService lifecycle;
    @Autowired BranchContractAssignmentRepository links;
    @Autowired BranchRoomListingRepository listingLinks;
    @Autowired BranchSlotRepository slots;
    @Autowired PropertyBranchRepository branches;
    @Autowired ListingRepository listings;
    @Autowired RentalRequestRepository rentals;
    @Autowired ContractRepository contracts;
    @Autowired ContractRevisionRepository revisions;
    @Autowired ContractClosureRepository closures;
    @Autowired ContractRenewalRepository renewals;
    @Autowired SignatureRequestRepository signatures;
    @Autowired ContractDocumentRepository documents;
    @Autowired MonthlyInvoiceRepository invoices;
    @Autowired ParkingReservationRepository reservations;
    @Autowired PaymentRequestRepository payments;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ContractTemplateVersionRepository templates;
    @MockitoBean ContractDataBuilder dataBuilder;
    @MockitoBean ContractFieldCatalog catalog;
    @MockitoBean ContractRenderService renderer;
    @MockitoBean DocumentConversionService conversion;
    @MockitoBean StorageService storage;
    @MockitoBean StorageProperties storageProperties;
    @MockitoBean com.hs.common.location.VietmapService vietmap;
    @MockitoBean ListingStatusService listingStatus;
    @MockitoBean ParkingReservationService parking;
    @MockitoBean DepositLedgerService depositLedger;
    @MockitoBean ContractDraftIntegrity integrity;
    @MockitoBean ContractListingSync listingSync;
    @MockitoBean ContractDraftResidents residents;
    @MockitoBean DepositAgreementService deposits;
    @MockitoBean AgreementSigningNotificationWriter notifications;
    @MockitoBean ContractAmendmentRenderer amendmentRenderer;
    @MockitoBean ContractAmendmentService amendments;
    @MockitoBean ContractTermsResolver terms;
    @MockitoBean ContractPartyKycGuard kyc;
    @MockitoBean RentalWorkflowSupport workflowSupport;
    @MockitoBean PaymentRequestService paymentService;
    @MockitoBean RenewalNotificationWriter renewalNotifications;
    @MockitoBean TerminationNotificationWriter terminationNotifications;
    @MockitoBean InvoiceNotificationWriter invoiceNotifications;

    @Test
    void repeatedReadsRunProjectionsAndCountQueriesWithoutDmlLocksOrChangedRows() {
        var map = map();
        var listing = listing(map.id(), OWNER);
        roomLinks.link(map.id(), OWNER, map.room(), listing.getId());
        var held = rental(listing, TODAY.plusDays(2));
        var root = lease(rental(listing(map.id(), OWNER), TODAY.minusMonths(1)), ContractStatus.ACTIVE);
        service.attach(map.id(), OWNER, root.getId(), map.room());
        var overdue = invoices.saveAndFlush(MonthlyInvoice.builder().contractId(root.getId()).periodIndex(1)
                .periodStart(TODAY.minusMonths(1)).periodEndExclusive(TODAY).totalAmount(RENT)
                .status(MonthlyInvoiceStatus.UNPAID).dueAt(TODAY.minusDays(1).atStartOfDay(BillingTime.ZONE).toInstant()).build());
        reservations.saveAndFlush(ParkingReservation.builder().contractId(root.getId()).listingId(root.getListingId())
                .rentalRequestId(root.getRentalRequestId()).branchId(root.getBranchId()).quantity(1)
                .vehicleType(VehicleType.MOTORBIKE).status(ParkingReservationStatus.ACTIVE)
                .startDate(TODAY.minusMonths(1)).endDateExclusive(TODAY.plusMonths(2)).build());
        payments.saveAndFlush(PaymentRequest.builder().invoiceId(overdue.getId()).contractId(root.getId())
                .rentalRequestId(root.getRentalRequestId()).payerId("tenant").payeeId(OWNER)
                .type(PaymentType.MONTHLY_RENT).direction(PaymentDirection.TENANT_TO_LANDLORD)
                .transferReference(uuid().substring(0, 18)).totalAmount(RENT)
                .status(PaymentStatus.CONFIRMED).build());
        var inTray = lease(rental(listing(null, OWNER), TODAY), ContractStatus.DRAFT);
        service.attach(map.id(), OWNER, inTray.getId(), null);
        var elsewhere = map();
        var otherBranch = lease(rental(listing(elsewhere.id(), OWNER), TODAY), ContractStatus.CLOSED);
        service.attach(elsewhere.id(), OWNER, otherBranch.getId(), null);
        var foreign = lease(rental(listing(map.id(), "owner-b"), TODAY), ContractStatus.DRAFT);
        var deposit = lease(rental(listing(null, OWNER), TODAY), ContractStatus.DRAFT);
        deposit.setAgreementKind("DEPOSIT");
        contracts.saveAndFlush(deposit);
        var child = lease(rental(listing(null, OWNER), TODAY), ContractStatus.DRAFT);
        child.setParentContractId(root.getId());
        contracts.saveAndFlush(child);
        var many = listings.saveAllAndFlush(IntStream.range(0, 101).mapToObj(i ->
                listingBuilder(map.id(), OWNER).build()).toList());
        var expectedListings = new ArrayList<>(many.stream().map(Listing::getId).toList());
        expectedListings.addAll(List.of(listing.getId(), root.getListingId()));
        expectedListings.sort(String::compareTo);
        var before = snapshot();

        for (int pass = 0; pass < 2; pass++) {
            var sql = new ArrayList<String>();
            SqlTrace.statements.set(sql);
            try {
                var result = service.assignments(map.id(), OWNER, 0, 100);
                var room = result.rooms().getFirst();
                assertEquals(map.room(), room.roomId());
                assertEquals(List.of(listing.getId()), room.listings().stream().map(l -> l.id()).toList());
                assertEquals(List.of(root.getId()), room.contracts().stream().map(c -> c.id()).toList());
                assertEquals(TODAY.minusMonths(1).plusMonths(3), room.contracts().getFirst().endDateExclusive());
                assertEquals(List.of(held.getId()), room.holds().stream().map(h -> h.rentalRequestId()).toList());
                assertEquals(inTray.getId(), result.unplaced().items().getFirst().id());
                assertEquals(103, result.unplaced().totalElements());
                assertEquals(3, service.assignments(map.id(), OWNER, 1, 100).unplaced().items().size());

                var candidateListings = service.candidates(map.id(), OWNER, "LISTING", 0, 100);
                assertEquals(103, candidateListings.totalElements());
                assertEquals(expectedListings.subList(0, 100), candidateListings.items().stream().map(i -> i.id()).toList());
                var tail = service.candidates(map.id(), OWNER, "LISTING", 1, 100);
                assertEquals(expectedListings.subList(100, 103), tail.items().stream().map(i -> i.id()).toList());

                var candidateContracts = service.candidates(map.id(), OWNER, "CONTRACT", 0, 1);
                assertTrue(candidateContracts.totalElements() >= 2);
                var ids = new HashSet<String>();
                for (int page = 0; page < candidateContracts.totalElements(); page++) {
                    var item = service.candidates(map.id(), OWNER, "CONTRACT", page, 1).items().getFirst();
                    ids.add(item.id());
                    if (item.id().equals(root.getId())) assertEquals(map.room(), item.roomId());
                }
                assertTrue(ids.containsAll(List.of(inTray.getId(), root.getId())));
                assertTrue(Collections.disjoint(ids, List.of(foreign.getId(), deposit.getId(), child.getId(), otherBranch.getId())));
                var denied = assertThrows(AppException.class, () -> service.assignments(map.id(), "owner-b", 0, 20));
                assertEquals(404, denied.getStatusCode().value());
            } finally {
                SqlTrace.statements.remove();
            }
            assertFalse(sql.isEmpty());
            for (String statement : sql) {
                String normalized = statement.stripLeading().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                assertTrue(normalized.startsWith("select "), statement);
                assertFalse(normalized.matches(".* for (?:update|no key update|share|key share)\\b.*"), statement);
            }
            assertEquals(before, snapshot());
        }
    }

    @Test
    void uniqueAndForeignKeysEnforceListingAndContractLinks() {
        var map = map();
        var listing = listing(map.id(), OWNER);
        var other = listing(map.id(), OWNER);
        var ended = lease(rental(listing, TODAY), ContractStatus.CLOSED);
        roomLinks.link(map.id(), OWNER, map.room(), listing.getId());
        roomLinks.link(map.id(), OWNER, map.room(), other.getId());
        roomLinks.link(map.id(), OWNER, map.room(), other.getId());
        service.attach(map.id(), OWNER, ended.getId(), map.room());
        assertEquals(2, listingLinks.findAllInBranch(map.id()).size());
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "insert into branch_room_listings (listing_id, room_id) values (?, ?)", listing.getId(), map.room()));
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "insert into branch_contract_assignments (contract_id, management_branch_id) values (?, ?)", ended.getId(), map.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into branch_room_listings (listing_id, room_id) values (?, ?)", uuid(), map.room()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update branch_room_listings set room_id = ? where listing_id = ?", uuid(), listing.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into branch_contract_assignments (contract_id, management_branch_id) values (?, ?)", uuid(), map.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update branch_contract_assignments set management_branch_id = ? where contract_id = ?", uuid(), ended.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update branch_contract_assignments set room_id = ? where contract_id = ?", uuid(), ended.getId()));
        assertEquals(400, assertThrows(AppException.class, () -> propertyBranches.deleteBranch(map.id(), OWNER))
                .getStatusCode().value());
    }

    @Test
    void deletingRoomWithHandedOverLeaseUnplacesHistoryAndUnlinksListings() {
        var map = map();
        var listing = listing(map.id(), OWNER);
        var ended = lease(rental(listing, TODAY), ContractStatus.EXPIRED);
        closures.saveAndFlush(ContractClosure.builder().contractId(ended.getId()).status("REFUND_PENDING")
                .acceptedAt(TODAY.atStartOfDay(BillingTime.ZONE).toInstant()).build());
        var businessBefore = businessSnapshot();
        roomLinks.link(map.id(), OWNER, map.room(), listing.getId());
        service.attach(map.id(), OWNER, ended.getId(), map.room());
        layouts.saveLayout(map.id(), OWNER, map.removeFirst());
        assertEquals(Optional.empty(), slots.findById(map.room()));
        assertTrue(listingLinks.findAllInBranch(map.id()).isEmpty());
        assertNull(links.findById(ended.getId()).orElseThrow().getRoomId());
        assertEquals(businessBefore, businessSnapshot());
    }

    @Test
    void endedLeaseInTrayBlocksBranchDeleteUntilDetached() {
        var map = map();
        var ended = lease(rental(listing(null, OWNER), TODAY), ContractStatus.CLOSED);
        service.attach(map.id(), OWNER, ended.getId(), null);
        assertEquals(ListingErrorCode.BRANCH_HAS_CONTRACT_LINKS.getCode(),
                assertThrows(AppException.class, () -> propertyBranches.deleteBranch(map.id(), OWNER)).getCode());
        service.detach(map.id(), OWNER, ended.getId());
        propertyBranches.deleteBranch(map.id(), OWNER);
        assertTrue(branches.findByIdAndOwnerIdAndActiveTrue(map.id(), OWNER).isEmpty());
        assertEquals(0, links.countByManagementBranchId(map.id()));
    }

    @Test
    void flagChangesOnlyTheSlotAndAcceptedHoldBlocksIt() {
        var map = map();
        var listing = listing(map.id(), OWNER);
        roomLinks.link(map.id(), OWNER, map.room(), listing.getId());
        var before = businessSnapshot();
        assertEquals(RoomManualStatus.EXTERNAL_RENT,
                roomLinks.setManualStatus(map.id(), OWNER, map.room(), RoomManualStatus.EXTERNAL_RENT));
        assertEquals(RoomManualStatus.EXTERNAL_RENT, service.assignments(map.id(), OWNER, 0, 20).rooms().getFirst().manualStatus());
        roomLinks.setManualStatus(map.id(), OWNER, map.room(), RoomManualStatus.NONE);
        assertEquals(before, businessSnapshot());

        rental(listing, TODAY.plusDays(1));
        assertEquals(409, assertThrows(AppException.class,
                () -> roomLinks.setManualStatus(map.id(), OWNER, map.room(), RoomManualStatus.REPAIR)).getStatusCode().value());
        assertEquals(RoomManualStatus.NONE, service.assignments(map.id(), OWNER, 0, 20).rooms().getFirst().manualStatus());
        assertEquals(404, assertThrows(AppException.class,
                () -> roomLinks.setManualStatus(map.id(), "owner-b", map.room(), RoomManualStatus.REPAIR)).getStatusCode().value());
    }

    @Test
    void aHoldAcceptedAfterTheFlagHidesItUntilTheHoldEnds() {
        var map = map();
        var listing = listing(map.id(), OWNER);
        roomLinks.link(map.id(), OWNER, map.room(), listing.getId());
        roomLinks.setManualStatus(map.id(), OWNER, map.room(), RoomManualStatus.REPAIR);

        var hold = rental(listing, TODAY.plusDays(1));
        var room = service.assignments(map.id(), OWNER, 0, 20).rooms().getFirst();
        assertEquals(hold.getId(), room.holds().getFirst().rentalRequestId());
        assertEquals(RoomManualStatus.NONE, room.manualStatus());
        assertEquals(RoomManualStatus.REPAIR, slots.findById(map.room()).orElseThrow().getManualStatus());

        hold.setStatus(RentalRequestStatus.CANCELLED_BY_OWNER);
        rentals.saveAndFlush(hold);
        assertEquals(RoomManualStatus.REPAIR, service.assignments(map.id(), OWNER, 0, 20).rooms().getFirst().manualStatus());
    }

    @Test
    void signingRenewalShowsTheOverlappingNextLeaseInTheTrayOnlyWhileTheConflictLasts() {
        var map = map();
        var currentRental = rental(listing(map.id(), OWNER), TODAY.minusMonths(2));
        var current = lease(currentRental, ContractStatus.ACTIVE);
        var startsOn = currentRental.getMoveInDate().plusMonths(3);
        var next = lease(rental(listing(map.id(), OWNER), startsOn), ContractStatus.DRAFT);
        service.attach(map.id(), OWNER, current.getId(), map.room());
        service.attach(map.id(), OWNER, next.getId(), map.room());
        assertEquals(map.room(), links.findById(next.getId()).orElseThrow().getRoomId());

        var agreement = lease(currentRental, ContractStatus.ACTIVE);
        agreement.setParentContractId(current.getId());
        agreement.setCurrentRevisionId(uuid());
        agreement = contracts.saveAndFlush(agreement);
        var renewal = renewals.saveAndFlush(ContractRenewal.builder().contractId(current.getId()).status("APPROVED")
                .months(1).startsOn(startsOn).endExclusive(currentRental.getMoveInDate().plusMonths(4))
                .signBy(TODAY.plusDays(2).atStartOfDay(BillingTime.ZONE).toInstant())
                .agreementContractId(agreement.getId()).build());
        for (var role : SignerRole.values()) {
            signatures.saveAndFlush(SignatureRequest.builder().contractId(agreement.getId())
                    .revisionId(agreement.getCurrentRevisionId()).sourceDocumentId(uuid()).docId(uuid())
                    .signerUserId(role == SignerRole.LANDLORD ? OWNER : current.getTenantId())
                    .signerRole(role).status(SignatureRequestStatus.SIGNED).build());
        }
        documents.saveAndFlush(ContractDocument.builder().contractId(agreement.getId())
                .revisionId(agreement.getCurrentRevisionId()).templateVersionId(agreement.getTemplateVersionId())
                .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.SIGNED_FINAL)
                .status(DocumentGenerationStatus.READY).build());

        lifecycle.applyRenewalAfterSignatures(agreement);
        assertEquals("APPLIED", renewals.findById(renewal.getId()).orElseThrow().getStatus());
        assertEquals(4, contracts.findById(current.getId()).orElseThrow().getEffectiveLeaseMonths());
        assertEquals(map.room(), links.findById(current.getId()).orElseThrow().getRoomId());
        var projected = service.assignments(map.id(), OWNER, 0, 20);
        assertTrue(projected.unplaced().items().stream().anyMatch(item -> item.id().equals(next.getId())));
        assertEquals(map.room(), links.findById(next.getId()).orElseThrow().getRoomId(),
                "The conflict is only projected; the stored room is kept");

        handOver(current);
        assertShownInRoom(map, next);
    }

    @Test
    void anOverdueOccupantOnlyHidesTheNextLeaseUntilHandover() {
        var map = map();
        var current = lease(rental(listing(map.id(), OWNER), TODAY.minusMonths(3)), ContractStatus.EXPIRED);
        var next = lease(rental(listing(map.id(), OWNER), TODAY), ContractStatus.DRAFT);
        // Both links were placed before their common boundary date.
        links.saveAllAndFlush(List.of(new BranchContractAssignment(current.getId(), map.id(), map.room()),
                new BranchContractAssignment(next.getId(), map.id(), map.room())));
        var before = snapshot();

        var conflicting = service.assignments(map.id(), OWNER, 0, 20);
        assertTrue(conflicting.unplaced().items().stream().anyMatch(item -> item.id().equals(next.getId())));
        assertEquals(ContractErrorCode.BRANCH_ROOM_SCHEDULE_CONFLICT.getCode(), assertThrows(AppException.class,
                () -> service.attach(map.id(), OWNER, next.getId(), map.room())).getCode());
        assertEquals(before, snapshot(), "Neither the read nor the rejected attach may write");

        handOver(current);
        assertShownInRoom(map, next);
    }

    private void handOver(Contract contract) {
        closures.saveAndFlush(ContractClosure.builder().contractId(contract.getId()).status("REFUND_PENDING")
                .acceptedAt(TODAY.atStartOfDay(BillingTime.ZONE).toInstant()).build());
    }

    private void assertShownInRoom(TestMap map, Contract contract) {
        var shown = service.assignments(map.id(), OWNER, 0, 20);
        assertTrue(shown.unplaced().items().stream().noneMatch(item -> item.id().equals(contract.getId())));
        assertTrue(shown.rooms().getFirst().contracts().stream().anyMatch(c -> c.id().equals(contract.getId())));
    }

    @Test
    void movingAListingDropsOnlyItsLinkAndKeepsContractInItsRoom() {
        var map = map();
        var listing = listing(map.id(), OWNER);
        var contract = lease(rental(listing, TODAY), ContractStatus.ACTIVE);
        roomLinks.link(map.id(), OWNER, map.room(), listing.getId());
        service.attach(map.id(), OWNER, contract.getId(), map.room());
        tx(() -> {
            var live = listings.findByIdForUpdate(listing.getId()).orElseThrow();
            live.setBranchId(null);
            roomLinks.dropLinkOutsideBranch(live.getId(), map.id(), null);
        });
        assertTrue(listingLinks.findById(listing.getId()).isEmpty());
        assertEquals(map.room(), links.findById(contract.getId()).orElseThrow().getRoomId());
        assertEquals("business-parking", contracts.findById(contract.getId()).orElseThrow().getBranchId());
    }

    @Test
    void movingAnUnlinkedListingCannotLeaveAConcurrentLinkInItsOldBranch() throws Exception {
        var from = map();
        var to = map();
        var listing = listing(from.id(), OWNER);

        interleave(() -> moveListing(listing.getId(), to.id()), () -> {}, () -> {
            try {
                roomLinks.link(from.id(), OWNER, from.room(), listing.getId());
            } catch (AppException e) {
                assertEquals(ListingErrorCode.LISTING_BRANCH_MISMATCH.getCode(), e.getCode());
            }
        });

        assertEquals(to.id(), listings.findById(listing.getId()).orElseThrow().getBranchId());
        assertTrue(listingLinks.findById(listing.getId()).isEmpty(), "The link must not point to the old branch");
    }

    @Test
    void parkingLockOrderAllowsLinkAndUnlinkToCommit() throws Exception {
        var map = map();
        var listing = listing(map.id(), OWNER);
        Runnable parkBranch = () -> branches.findByIdForUpdate(map.id()).orElseThrow();
        Runnable parkListing = () -> listings.findByIdForUpdate(listing.getId()).orElseThrow();

        interleave(parkBranch, parkListing, () -> roomLinks.link(map.id(), OWNER, map.room(), listing.getId()));
        assertEquals(map.room(), listingLinks.findById(listing.getId()).orElseThrow().getRoomId());
        interleave(parkBranch, parkListing, () -> roomLinks.unlink(map.id(), OWNER, map.room(), listing.getId()));
        assertTrue(listingLinks.findById(listing.getId()).isEmpty());

        interleave(parkListing, parkBranch, () -> roomLinks.link(map.id(), OWNER, map.room(), listing.getId()));
        assertEquals(map.room(), listingLinks.findById(listing.getId()).orElseThrow().getRoomId());
    }

    @Test
    void parkingOnEitherBranchAllowsAListingMoveToCommit() throws Exception {
        var from = map();
        var to = map();
        for (String parkingBranch : List.of(from.id(), to.id())) {
            var listing = listing(from.id(), OWNER);
            roomLinks.link(from.id(), OWNER, from.room(), listing.getId());
            interleave(() -> branches.findByIdForUpdate(parkingBranch).orElseThrow(),
                    () -> listings.findByIdForUpdate(listing.getId()).orElseThrow(),
                    () -> tx(() -> moveListing(listing.getId(), to.id())));
            assertEquals(to.id(), listings.findById(listing.getId()).orElseThrow().getBranchId());
            assertTrue(listingLinks.findById(listing.getId()).isEmpty());
        }
    }

    @Test
    void deletingBranchFirstMakesListingWriteObserveInactiveBranch() throws Exception {
        var map = map();
        race(() -> propertyBranches.deleteBranch(map.id(), OWNER), () -> tx(() ->
                assertEquals(Optional.of(false), branches.lockForListingWrite(map.id()))));
    }

    @Test
    void movingListingFirstMakesBranchDeleteSeeItsNewListing() throws Exception {
        var from = map();
        var to = map();
        var listing = listing(from.id(), OWNER);
        race(() -> moveListing(listing.getId(), to.id()), () ->
                assertEquals(400, assertThrows(AppException.class, () -> propertyBranches.deleteBranch(to.id(), OWNER))
                        .getStatusCode().value()));
        assertTrue(branches.findByIdAndActiveTrue(to.id()).isPresent());
    }

    @Test
    void autoPlacementUsesTheNewActiveBranchWhenListingMovesWhileWaiting() throws Exception {
        var from = map();
        var to = map();
        var listing = listing(from.id(), OWNER);
        var contract = lease(rental(listing, TODAY), ContractStatus.DRAFT);
        roomLinks.link(from.id(), OWNER, from.room(), listing.getId());

        race(() -> {
            var live = listings.findByIdForUpdate(listing.getId()).orElseThrow();
            live.setBranchId(to.id());
            roomLinks.dropLinkOutsideBranch(live.getId(), from.id(), to.id());
        }, () -> service.autoLink(contract.getId()));

        assertTrue(listingLinks.findById(listing.getId()).isEmpty());
        var assignment = links.findById(contract.getId())
                .orElseThrow(() -> new AssertionError("The listing now belongs to an active branch of the same owner"));
        assertEquals(to.id(), assignment.getManagementBranchId());
        assertNull(assignment.getRoomId());
    }

    @Test
    void autoPlacementFollowsAnInFlightMoveEvenWhenTheListingHasNoRoomLink() throws Exception {
        var from = map();
        var to = map();
        var listing = listing(from.id(), OWNER);
        var contract = lease(rental(listing, TODAY), ContractStatus.DRAFT);

        interleave(() -> moveListing(listing.getId(), to.id()), () -> {}, () -> service.autoLink(contract.getId()));

        var assignment = links.findById(contract.getId()).orElseThrow();
        assertEquals(to.id(), assignment.getManagementBranchId());
        assertNull(assignment.getRoomId());
    }

    @Test
    void concurrentManualAndAutoPlacementSerializeAndADeletedRoomFallsBackToTray() throws Exception {
        var map = map();
        var first = lease(rental(listing(map.id(), OWNER), TODAY), ContractStatus.ACTIVE);
        var competingListing = listing(map.id(), OWNER);
        var competing = lease(rental(competingListing, TODAY), ContractStatus.DRAFT);
        roomLinks.link(map.id(), OWNER, map.room(), competingListing.getId());

        race(() -> service.attach(map.id(), OWNER, first.getId(), map.room()), () -> service.autoLink(competing.getId()));
        assertEquals(map.room(), links.findById(first.getId()).orElseThrow().getRoomId());
        assertNull(links.findById(competing.getId()).orElseThrow().getRoomId());
        assertEquals(ListingErrorCode.BRANCH_ROOM_IN_USE.getCode(),
                assertThrows(AppException.class, () -> layouts.saveLayout(map.id(), OWNER, map.removeFirst())).getCode());
        assertEquals(ContractErrorCode.BRANCH_ROOM_SCHEDULE_CONFLICT.getCode(), assertThrows(AppException.class,
                () -> service.attach(map.id(), OWNER, competing.getId(), map.room())).getCode());

        service.detach(map.id(), OWNER, first.getId());
        service.detach(map.id(), OWNER, competing.getId());
        race(() -> layouts.saveLayout(map.id(), OWNER, map.removeFirst()), () -> service.autoLink(competing.getId()));
        assertEquals(Optional.empty(), slots.findById(map.room()));
        assertTrue(listingLinks.findById(competingListing.getId()).isEmpty());
        assertNull(links.findById(competing.getId()).orElseThrow().getRoomId());
    }

    @Test
    void layoutDeleteWaitingOnAttachRejectsInsteadOfLeavingAnOrphan() throws Exception {
        var map = map();
        var contract = lease(rental(listing(null, OWNER), TODAY), ContractStatus.ACTIVE);
        race(() -> service.attach(map.id(), OWNER, contract.getId(), map.room()), () ->
                assertEquals(ListingErrorCode.BRANCH_ROOM_IN_USE.getCode(), assertThrows(AppException.class,
                        () -> layouts.saveLayout(map.id(), OWNER, map.removeFirst())).getCode()));
        assertTrue(slots.existsById(map.room()));
        assertEquals(map.room(), links.findById(contract.getId()).orElseThrow().getRoomId());
    }

    @Test
    void aBranchDeletedWhileAttachWaitsCannotReceiveATrayLink() throws Exception {
        var map = map();
        var contract = lease(rental(listing(null, OWNER), TODAY), ContractStatus.CLOSED);
        race(() -> propertyBranches.deleteBranch(map.id(), OWNER), () ->
                assertEquals(404, assertThrows(AppException.class,
                        () -> service.attach(map.id(), OWNER, contract.getId(), null)).getStatusCode().value()));
        assertTrue(links.findById(contract.getId()).isEmpty());
        assertTrue(branches.findByIdAndOwnerIdAndActiveTrue(map.id(), OWNER).isEmpty());
    }

    @Test
    void realContractCreateCommitsWithRoomOrTrayAndRetryDoesNotReattach() {
        stubCreationInputs();
        var map = map();
        var first = paidRental(map, true);
        var next = paidRental(map, true);
        UserContextHolder.set(new UserContext(OWNER, null));
        try {
            var manual = creation.createDraft(new CreateContractDraftRequest(first.getId()));
            awaitAutoLinks();
            assertEquals(map.room(), links.findById(manual.getId()).orElseThrow().getRoomId());
            var automatic = creation.createDraftAutomatically(next.getId());
            awaitAutoLinks();
            assertEquals(map.id(), links.findById(automatic.getId()).orElseThrow().getManagementBranchId());
            assertNull(links.findById(automatic.getId()).orElseThrow().getRoomId());
            var before = businessSnapshot();
            service.detach(map.id(), OWNER, manual.getId());
            assertEquals(manual.getId(), creation.createDraft(new CreateContractDraftRequest(first.getId())).getId());
            awaitAutoLinks();
            assertTrue(links.findById(manual.getId()).isEmpty());
            assertEquals(before, businessSnapshot());
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void postgresRejectingTheAutoLinkWriteDoesNotRollBackDraftRevisionOrPayment() {
        stubCreationInputs();
        var map = map();
        var rental = paidRental(map, true);
        // NOT VALID avoids touching earlier fixtures while forcing an actual SQL error for this branch.
        jdbc.execute("alter table branch_contract_assignments add constraint p2_test_link_failure "
                + "check (management_branch_id <> '" + map.id() + "') not valid");
        try {
            var draft = creation.createDraftAutomatically(rental.getId());
            awaitAutoLinks();
            var saved = contracts.findById(draft.getId()).orElseThrow();
            assertNotNull(saved.getCurrentRevisionId());
            assertTrue(revisions.existsById(saved.getCurrentRevisionId()));
            assertEquals(saved.getId(), payments.findById(saved.getRentalPaymentId()).orElseThrow().getContractId());
            assertTrue(links.findById(saved.getId()).isEmpty());
            assertNull(saved.getBranchId());
            assertTrue(listingLinks.findById(rental.getListing().getId()).isPresent());
        } finally {
            jdbc.execute("alter table branch_contract_assignments drop constraint p2_test_link_failure");
        }
    }

    /** The queue is single-threaded and FIFO: once a later task has run, every queued link has finished. */
    private void awaitAutoLinks() {
        var queue = (ExecutorService) ReflectionTestUtils.getField(autoLink, "links");
        assertDoesNotThrow(() -> queue.submit(() -> {}).get(10, TimeUnit.SECONDS));
    }

    private void stubCreationInputs() {
        var template = ContractTemplate.builder().id("template").build();
        when(templates.findCurrentPublishedSystemVersions(ListingCategory.ROOM)).thenReturn(List.of(
                ContractTemplateVersion.builder().id("version").template(template).build()));
        when(dataBuilder.build(any(), any(), any())).thenReturn(ContractDataBuilder.ContractSnapshots.builder()
                .landlord(Map.of()).tenant(Map.of()).property(Map.of("address", "Test address"))
                .lease(Map.of("durationMonths", 3)).financial(Map.of("amountValue", "3000000"))
                .charges(List.of()).equipments(List.of()).amenities(List.of()).build());
    }

    private RentalRequest paidRental(TestMap map, boolean link) {
        var listing = listing(map.id(), OWNER);
        if (link) roomLinks.link(map.id(), OWNER, map.room(), listing.getId());
        var rental = rental(listing, TODAY);
        payments.saveAndFlush(PaymentRequest.builder().rentalRequestId(rental.getId())
                .listingId(listing.getId()).payerId(rental.getRenterId()).payeeId(OWNER)
                .totalAmount(RENT).transferReference(uuid().substring(0, 18)).type(PaymentType.INITIAL)
                .direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED)
                .confirmedAt(TODAY.atStartOfDay(BillingTime.ZONE).toInstant()).build());
        return rental;
    }

    private TestMap map() {
        String id = branches.saveAndFlush(PropertyBranch.builder().id(uuid()).ownerId(OWNER).name("P2 test branch")
                .category(ListingCategory.ROOM).totalUnits(2).motorbikeParkingCapacity(5).build()).getId();
        var tree = layouts.saveLayout(id, OWNER, new SaveBranchLayoutRequest(0L,
                List.of(new Floor(null, "Tầng 1", List.of(new Row(null, "Dãy A",
                        List.of(new Slot(null, "101"), new Slot(null, "102"))))))));
        return new TestMap(id, tree);
    }

    private record TestMap(String id, BranchLayoutResponse tree) {
        String room() { return tree.floors().getFirst().rows().getFirst().slots().getFirst().id(); }

        SaveBranchLayoutRequest removeFirst() {
            var floor = tree.floors().getFirst();
            var row = floor.rows().getFirst();
            var keep = row.slots().getLast();
            return new SaveBranchLayoutRequest(tree.revision(),
                    List.of(new Floor(floor.id(), floor.label(), List.of(new Row(row.id(), row.label(),
                            List.of(new Slot(keep.id(), keep.roomCode())))))));
        }
    }

    private Listing listing(String branch, String owner) {
        return listings.saveAndFlush(listingBuilder(branch, owner).build());
    }

    private static Listing.ListingBuilder listingBuilder(String branch, String owner) {
        return Listing.builder().branchId(branch).ownerId(owner).title("Tin trùng tên").description("Test only")
                .category(ListingCategory.ROOM).status(ListingStatus.RESERVED).priceAmount(RENT)
                .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
                .availableFrom(TODAY).minimumLeaseMonths(1).depositType(DepositType.NONE);
    }

    private RentalRequest rental(Listing listing, LocalDate moveIn) {
        return rentals.saveAndFlush(RentalRequest.builder().id(uuid()).listing(listing).ownerId(listing.getOwnerId())
                .renterId("tenant").renterName("Khách thử").renterPhone("0900000000").status(RentalRequestStatus.ACCEPTED)
                .moveInDate(moveIn).leaseMonths(3).occupantCount(1).motorbikeCount(0).carCount(0)
                .monthlyRentPrice(RENT).depositAmount(BigDecimal.ZERO).build());
    }

    private Contract lease(RentalRequest rental, ContractStatus status) {
        return contracts.saveAndFlush(Contract.builder().contractNumber("P2-" + uuid()).rentalRequestId(rental.getId())
                .listingId(rental.getListing().getId()).landlordId(rental.getOwnerId()).tenantId(rental.getRenterId())
                .templateId("template").templateVersionId("version").branchId("business-parking").status(status).build());
    }

    private Map<String, String> snapshot() {
        Map<String, String> rows = new TreeMap<>();
        for (String table : jdbc.queryForList("select quote_ident(tablename) from pg_tables where schemaname = current_schema()", String.class)) {
            rows.put(table, jdbc.queryForObject(
                    "select jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text from " + table + " t", String.class));
        }
        return rows;
    }

    private Map<String, String> businessSnapshot() {
        var rows = snapshot();
        rows.keySet().removeAll(Set.of("branch_layouts", "branch_floors", "branch_rows", "branch_slots",
                "branch_room_listings", "branch_contract_assignments"));
        return rows;
    }

    private void race(Runnable holding, Runnable waiting) throws Exception {
        var locked = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> tx(() -> {
                holding.run();
                locked.countDown();
                await(release);
            }));
            try {
                await(locked);
                var second = workers.submit(() -> {
                    started.countDown();
                    waiting.run();
                });
                await(started);
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private void moveListing(String listingId, String to) {
        var listing = listings.findByIdForUpdate(listingId).orElseThrow();
        String from = listing.getBranchId();
        listing.setBranchId(to);
        listings.flush();
        assertEquals(Optional.of(true), branches.lockForListingWrite(to));
        roomLinks.dropLinkOutsideBranch(listingId, from, to);
    }

    private void interleave(Runnable firstLock, Runnable secondLock, Runnable competing) throws Exception {
        var locked = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> tx(() -> {
                firstLock.run();
                locked.countDown();
                await(release);
                secondLock.run();
            }));
            try {
                await(locked);
                var second = workers.submit(() -> {
                    started.countDown();
                    competing.run();
                });
                await(started);
                try {
                    second.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waitingForFirstLock) {
                    // The other lock order is also allowed to wait, provided both transactions commit.
                }
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for the other transaction");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
}
