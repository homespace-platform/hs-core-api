package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.CreatePropertyBranchRequest;
import com.hs.listing.model.ParkingReservation;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ParkingReservationStatus;
import com.hs.listing.model.constant.VehicleType;
import com.hs.listing.repository.AmenityRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ParkingReservationRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** R-B (Q14), opt-in: lowering capacity must wait for a concurrent hold that already took the branch lock. */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL",
        matches = "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/hs_r2_parking_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}",
        "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '10s'",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ParkingReservationService.class)
class BranchCapacityRacePostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = PropertyBranchRepository.class)
    static class Config {}

    @Autowired PropertyBranchRepository branches;
    @Autowired ParkingReservationRepository reservations;
    @Autowired ListingRepository listings;
    @Autowired AmenityRepository amenities;
    @Autowired ParkingReservationService parking;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void loweringCapacityWaitsForTheConcurrentHoldAndThenSeesIt() throws Exception {
        var branch = branches.saveAndFlush(PropertyBranch.builder().id(UUID.randomUUID().toString()).ownerId("o")
                .name("B").category(ListingCategory.ROOM).motorbikeParkingCapacity(10).build());
        hold(branch.getId(), 8);
        var service = new PropertyBranchService(branches, amenities, listings, parking,
                mock(com.hs.storage.repository.StorageObjectRepository.class), mock(com.hs.storage.config.StorageProperties.class));
        ReflectionTestUtils.setField(service, "vietmap", mock(com.hs.common.location.VietmapService.class));
        var tx = new TransactionTemplate(transactions);
        var locked = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            // Approval side: takes the branch lock like every hold path, adds 2 places, commits a moment later.
            var approval = pool.submit(() -> tx.executeWithoutResult(s -> {
                branches.findByIdForUpdate(branch.getId()).orElseThrow();
                hold(branch.getId(), 2);
                locked.countDown();
                try { Thread.sleep(1500); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var request = CreatePropertyBranchRequest.builder().name("B").category(ListingCategory.ROOM).refId("vm:x")
                    .motorbikeParkingCapacity(8).carParkingCapacity(0).build();
            var error = assertThrows(AppException.class, () -> tx.executeWithoutResult(s -> service.updateBranch(branch.getId(), "o", request)));
            assertEquals(ListingErrorCode.PARKING_CAPACITY_CHANGED.getCode(), error.getCode());
            approval.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(10, jdbc.queryForObject("select motorbike_parking_capacity from property_branches where id = ?", Integer.class, branch.getId()));
    }

    private void hold(String branchId, int quantity) {
        reservations.saveAndFlush(ParkingReservation.builder().branchId(branchId).listingId("l").rentalRequestId(UUID.randomUUID().toString())
                .vehicleType(VehicleType.MOTORBIKE).quantity(quantity).startDate(LocalDate.of(2027, 1, 1))
                .endDateExclusive(LocalDate.of(2027, 2, 1)).status(ParkingReservationStatus.ACTIVE).build());
    }
}
