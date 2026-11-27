package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.SaveBranchLayoutRequest;
import com.hs.listing.dto.request.SaveBranchLayoutRequest.Floor;
import com.hs.listing.dto.request.SaveBranchLayoutRequest.Row;
import com.hs.listing.dto.request.SaveBranchLayoutRequest.Slot;
import com.hs.listing.dto.response.BranchLayoutResponse;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.PropertyBranchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Hibernate owns create/drop in this dedicated disposable database. */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL",
        matches = "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/hs_branch_layout_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}",
        "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(BranchLayoutService.class)
class BranchLayoutPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = PropertyBranchRepository.class)
    static class Config {}

    private static final String OWNER = "owner-a";

    @Autowired BranchLayoutService service;
    @Autowired PropertyBranchRepository branches;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void hibernateCreatesMappedTablesAndGetDoesNotPopulateAnOldBranch() {
        assertEquals(List.of("branch_floors", "branch_layouts", "branch_rows", "branch_slots"),
                jdbc.queryForList("""
                        select table_name from information_schema.tables
                        where table_schema = current_schema()
                          and table_name in ('branch_layouts','branch_floors','branch_rows','branch_slots')
                        order by table_name
                        """, String.class));
        var branch = branch();
        var empty = new BranchLayoutResponse(0, List.of());
        assertOtherOwnerCannotAccess(branch.getId(), 0);
        assertEquals(empty, service.getLayout(branch.getId(), OWNER));
        assertEquals(empty, service.getLayout(branch.getId(), OWNER));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from branch_layouts where branch_id = ?", Long.class, branch.getId()));

        var saved = service.saveLayout(branch.getId(), OWNER, request(0, new Slot(null, "A1"), new Slot(null, null)));
        var floor = saved.floors().getFirst();
        var row = floor.rows().getFirst();
        assertOtherOwnerCannotAccess(branch.getId(), 1);
        UUID.fromString(floor.id());
        UUID.fromString(row.id());
        row.slots().forEach(slot -> UUID.fromString(slot.id()));
        assertEquals(saved, service.getLayout(branch.getId(), OWNER));
        assertEquals(1L, jdbc.queryForObject(
                "select revision from branch_layouts where branch_id = ?", Long.class, branch.getId()));
        var unchanged = branches.findById(branch.getId()).orElseThrow();
        assertEquals(7, unchanged.getTotalUnits());
        assertEquals(12, unchanged.getMotorbikeParkingCapacity());
    }

    @Test
    void swapReuseAndMoveKeepIdsWithRealUniqueAndForeignKeys() {
        String id = branch().getId();
        var initial = service.saveLayout(id, OWNER, new SaveBranchLayoutRequest(0L, List.of(
                new Floor(null, "Move out", List.of(new Row(null, "Keep row",
                        List.of(new Slot(null, "A1"), new Slot(null, "A2"), new Slot(null, "A3"))),
                        new Row(null, "Delete row", List.of(new Slot(null, null))))),
                new Floor(null, "Destination", List.of()))));
        var oldFloor = initial.floors().getFirst();
        var destination = initial.floors().getLast();
        var keepRow = oldFloor.rows().getFirst();
        var droppedRow = oldFloor.rows().getLast();
        var cells = keepRow.slots();
        var aisle = droppedRow.slots().getFirst();
        String keepId = cells.get(0).id();
        String otherId = cells.get(1).id();
        String removeId = cells.get(2).id();

        var change = new SaveBranchLayoutRequest(1L, List.of(new Floor(destination.id(), "Renamed destination", List.of(
                new Row(null, "New row", List.of(new Slot(keepId, " a2 "), new Slot(aisle.id(), null))),
                new Row(keepRow.id(), "Moved row", List.of(new Slot(null, "A3"), new Slot(otherId, "A1")))))));
        var saved = service.saveLayout(id, OWNER, change);
        var floor = saved.floors().getFirst();
        assertEquals(2, saved.revision());
        assertEquals(1, saved.floors().size());
        assertEquals(destination.id(), floor.id());
        assertEquals("Renamed destination", floor.label());
        assertEquals(List.of(new BranchLayoutResponse.Slot(keepId, "a2"),
                new BranchLayoutResponse.Slot(aisle.id(), null)), floor.rows().getFirst().slots());
        var moved = floor.rows().getLast();
        assertEquals(keepRow.id(), moved.id());
        assertNotEquals(removeId, moved.slots().getFirst().id());
        assertEquals("A3", moved.slots().getFirst().roomCode());
        assertEquals(new BranchLayoutResponse.Slot(otherId, "A1"), moved.slots().getLast());
        assertEquals(saved, service.getLayout(id, OWNER));

        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "update branch_slots set normalized_room_code = ? where id = ?", "A2", otherId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from branch_slots where id = ?", Long.class, removeId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from branch_rows where id = ?", Long.class, droppedRow.id()));
        assertEquals(0L, jdbc.queryForObject("select count(*) from branch_floors where id = ?", Long.class, oldFloor.id()));
    }

    @Test
    void concurrentSavesWithTheSameRevisionCommitOneIntactTree() throws Exception {
        String id = branch().getId();
        var ready = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var saved = service.saveLayout(id, OWNER, request(0, new Slot(null, "A1"), new Slot(null, "A2")));
                ready.countDown();
                await(commit);
                return saved;
            }));
            try {
                await(ready);
                var second = workers.submit(() -> {
                    secondStarted.countDown();
                    return assertThrows(AppException.class,
                            () -> service.saveLayout(id, OWNER, request(0, new Slot(null, "B1"))));
                });
                await(secondStarted);
                assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
                commit.countDown();

                assertEquals(ListingErrorCode.BRANCH_LAYOUT_STALE.getCode(), second.get(10, TimeUnit.SECONDS).getCode());
                assertEquals(first.get(10, TimeUnit.SECONDS), service.getLayout(id, OWNER));
            } finally {
                commit.countDown();
            }
        }
    }

    @Test
    void failedLayoutDoesNotUndoAnAlreadyCreatedBranchOrLeavePartialTree() {
        String id = branch().getId();
        assertThrows(AppException.class, () -> service.saveLayout(id, OWNER,
                request(0, new Slot(null, "A1"), new Slot(null, " a1 "))));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.saveLayout(id, OWNER, request(0, new Slot(null, "A1")));
            throw new IllegalStateException("Abort after layout writes");
        }));

        assertTrue(branches.findByIdAndOwnerIdAndActiveTrue(id, OWNER).isPresent());
        assertEquals(new BranchLayoutResponse(0, List.of()), service.getLayout(id, OWNER));
    }

    private void assertOtherOwnerCannotAccess(String branchId, long revision) {
        var read = assertThrows(AppException.class, () -> service.getLayout(branchId, "owner-b"));
        var write = assertThrows(AppException.class, () -> service.saveLayout(
                branchId, "owner-b", request(revision, new Slot(null, "B1"))));
        assertEquals(404, read.getStatusCode().value());
        assertEquals(read.getStatusCode(), write.getStatusCode());
        assertEquals(read.getErrorMessage(), write.getErrorMessage());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for the other transaction");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private PropertyBranch branch() {
        return branches.saveAndFlush(PropertyBranch.builder().id(UUID.randomUUID().toString()).ownerId(OWNER)
                .name("Branch test").category(ListingCategory.ROOM).totalUnits(7).motorbikeParkingCapacity(12).build());
    }

    private static SaveBranchLayoutRequest request(long revision, Slot... cells) {
        return new SaveBranchLayoutRequest(revision,
                List.of(new Floor(null, "Floor", List.of(new Row(null, "Row", List.of(cells))))));
    }
}
