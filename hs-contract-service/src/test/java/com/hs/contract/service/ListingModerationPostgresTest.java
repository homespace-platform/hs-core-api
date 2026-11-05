package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractClosure;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.ContractClosureRepository;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ListingStatusService;
import com.hs.listing.service.ViewingAppointmentService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in; schema creation is restricted to a separate, disposable moderation database. */
@EnabledIfEnvironmentVariable(named="HS_LISTING_REVIEW_PG_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_listing_review_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_LISTING_REVIEW_PG_URL}",
        "spring.datasource.username=postgres","spring.datasource.password=review-test-only",
        "spring.jpa.hibernate.ddl-auto=create-drop","listing.publication-duration-days=30"})
@Import(ListingStatusService.class)
class ListingModerationPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses={ListingRepository.class,ContractRepository.class})
    static class Config {}
    @Autowired ListingRepository listings;
    @Autowired RentalRequestRepository rentals;
    @Autowired ContractRepository contracts;
    @Autowired ContractClosureRepository closures;
    @Autowired ListingStatusService statuses;
    @Autowired EntityManager entityManager;
    @MockitoBean ViewingAppointmentService appointments;

    private Listing listing() {
        return listings.saveAndFlush(Listing.builder().ownerId("owner").title("Moderation test").description("Test")
                .category(ListingCategory.ROOM).status(ListingStatus.PENDING_REVIEW).priceAmount(new BigDecimal("3000000"))
                .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
                .availableFrom(LocalDate.now()).minimumLeaseMonths(1).depositType(DepositType.NONE).build());
    }
    private RentalRequest rental(Listing listing,RentalRequestStatus status) {
        return rentals.saveAndFlush(RentalRequest.builder().id(UUID.randomUUID().toString()).listing(listing).ownerId("owner")
                .renterId("tenant").renterName("Test tenant").renterPhone("0900000000").status(status)
                .moveInDate(LocalDate.now()).leaseMonths(3).occupantCount(1).motorbikeCount(0).carCount(0)
                .monthlyRentPrice(new BigDecimal("3000000")).depositAmount(BigDecimal.ZERO).build());
    }
    private Contract contract(Listing listing,ContractStatus status) {
        return contracts.saveAndFlush(Contract.builder().contractNumber(UUID.randomUUID().toString())
                .listingId(listing.getId()).rentalRequestId(UUID.randomUUID().toString()).landlordId("owner").tenantId("tenant")
                .templateId("template").templateVersionId("version").status(status).build());
    }

    @Test void acceptedRentalBlocksPublicationDespiteAnIncorrectPendingStatus() {
        var listing=listing(); rental(listing,RentalRequestStatus.ACCEPTED);
        assertTrue(listings.hasOccupiedRental(listing.getId()));
        assertThrows(AppException.class,()->statuses.changeByAdmin("admin",listing.getId(),ListingStatus.PUBLISHED,null));
        assertEquals(ListingStatus.PENDING_REVIEW,listing.getStatus()); assertNull(listing.getPublishedAt());
    }

    @ParameterizedTest @EnumSource(value=ContractStatus.class,names={"DRAFT","PENDING_REVIEW","LANDLORD_SIGNATURE_PENDING","TENANT_SIGNATURE_PENDING","ACTIVE","EXPIRED","TERMINATED"})
    void rootLeaseBlocksRepublishingUntilVerifiedPossessionReturn(ContractStatus status) {
        var listing=listing(); rental(listing,RentalRequestStatus.COMPLETED); var contract=contract(listing,status);
        assertTrue(listings.hasOccupiedRental(listing.getId()));
        closures.saveAndFlush(ContractClosure.builder().contractId(contract.getId()).status("PENDING").build());
        assertTrue(listings.hasOccupiedRental(listing.getId()));
        var closure=closures.findById(contract.getId()).orElseThrow(); closure.setAcceptedAt(Instant.now());
        closures.saveAndFlush(closure);
        assertFalse(listings.hasOccupiedRental(listing.getId()),"Confirmed vacant possession releases the room even while refund is pending");
    }

    @ParameterizedTest @EnumSource(value=ContractStatus.class,names={"CANCELLED","CLOSED"})
    void terminalContractsAndCancelledRequestsDoNotBlockPublication(ContractStatus status) {
        var listing=listing(); rental(listing,RentalRequestStatus.CANCELLED_BY_SYSTEM); contract(listing,status);
        assertFalse(listings.hasOccupiedRental(listing.getId()));
        statuses.changeByAdmin("admin",listing.getId(),ListingStatus.PUBLISHED,null);
        assertEquals(ListingStatus.PUBLISHED,listing.getStatus()); assertNotNull(listing.getExpiresAt());
    }

    @Test void depositAndAppendixDocumentsDoNotCreateIndependentOccupancy() {
        var listing=listing(); var deposit=contract(listing,ContractStatus.ACTIVE); deposit.setAgreementKind("DEPOSIT"); contracts.saveAndFlush(deposit);
        var appendix=contract(listing,ContractStatus.ACTIVE); appendix.setParentContractId(deposit.getId());
        appendix.setAgreementKind("AMENDMENT"); contracts.saveAndFlush(appendix);
        assertFalse(listings.hasOccupiedRental(listing.getId()));
        var other=listing(); rental(other,RentalRequestStatus.ACCEPTED); contract(other,ContractStatus.ACTIVE);
        assertFalse(listings.hasOccupiedRental(listing.getId()),"A different room must not block this listing");
    }

    @Test void moderationContextSurvivesReloadAndSigningBeforeApproval() {
        var listing=listing(); listing.setStatus(ListingStatus.RESERVED); listing.setPendingEditId("edit"); listings.saveAndFlush(listing);
        statuses.beginContentReview(listing,"owner"); entityManager.flush(); entityManager.clear();
        var reloaded=listings.findById(listing.getId()).orElseThrow();
        assertEquals(ListingStatus.PENDING_REVIEW,reloaded.getStatus()); assertEquals(ListingStatus.RESERVED,reloaded.getOperationalStatus());
        statuses.markRentedByContract(reloaded.getId(),"tenant"); entityManager.flush(); entityManager.clear();
        reloaded=listings.findById(listing.getId()).orElseThrow();
        assertEquals(ListingStatus.PENDING_REVIEW,reloaded.getStatus()); assertEquals(ListingStatus.RENTED,reloaded.getOperationalStatus());
        statuses.finishContentReview(reloaded,"admin",true,null); entityManager.flush(); entityManager.clear();
        var approved=listings.findById(listing.getId()).orElseThrow();
        assertEquals(ListingStatus.RENTED,approved.getStatus()); assertNull(approved.getReviewOperationalStatus());
        assertNull(approved.getReviewOriginStatus());
    }
}
