package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.response.RentalPaymentResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalPayment;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalPaymentStatus;
import com.hs.listing.model.constant.RentalPaymentType;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalPaymentRepository;
import com.hs.listing.repository.RentalRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RentalPaymentServiceTest {

    private RentalPaymentRepository rentalPaymentRepository;
    private RentalRequestRepository rentalRequestRepository;
    private ParkingReservationService parkingReservationService;
    private RentalPaymentService rentalPaymentService;

    @BeforeEach
    void setUp() {
        rentalPaymentRepository = mock(RentalPaymentRepository.class);
        rentalRequestRepository = mock(RentalRequestRepository.class);
        parkingReservationService = mock(ParkingReservationService.class);

        rentalPaymentService = new RentalPaymentService(
                rentalPaymentRepository,
                rentalRequestRepository,
                parkingReservationService,
                "24h"
        );
    }

    private Listing createListing(String id, String ownerId, BigDecimal price) {
        return Listing.builder()
                .id(id)
                .ownerId(ownerId)
                .title("Căn hộ dịch vụ cao cấp")
                .priceAmount(price)
                .status(ListingStatus.RESERVED)
                .build();
    }

    private RentalRequest createRentalRequest(String id, Listing listing, String renterId, String ownerId) {
        return RentalRequest.builder()
                .id(id)
                .listing(listing)
                .renterId(renterId)
                .ownerId(ownerId)
                .status(RentalRequestStatus.ACCEPTED)
                .monthlyRentPrice(new BigDecimal("5000000"))
                .effectiveMonthlyRent(new BigDecimal("5000000"))
                .estimatedMonthlyCharges(new BigDecimal("300000"))
                .depositAmount(new BigDecimal("5000000"))
                .estimatedInitialTotal(new BigDecimal("10300000"))
                .costBreakdownSnapshot("[{\"displayName\":\"Phí dịch vụ\",\"unitAmount\":300000}]")
                .excludedChargesSnapshot("[]")
                .holdExpiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    @DisplayName("1 & 2. Accept tạo payment PENDING đúng amount từ RentalRequest snapshot")
    void createInitialPayment_usesRentalRequestSnapshot() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("7000000")); // Giá listing đã đổi lên 7tr
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1"); // Snapshot lưu 5tr

        when(rentalPaymentRepository.findByRentalRequestIdAndType("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.empty());
        when(rentalPaymentRepository.save(any(RentalPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant expiresAt = Instant.now().plusSeconds(3600);
        RentalPayment payment = rentalPaymentService.createInitialPayment(req, expiresAt);

        assertNotNull(payment);
        assertEquals("req-1", payment.getRentalRequestId());
        assertEquals(RentalPaymentStatus.PENDING, payment.getStatus());
        assertEquals(RentalPaymentType.INITIAL_PAYMENT, payment.getType());
        assertEquals(new BigDecimal("5000000"), payment.getMonthlyRent()); // Lấy từ snapshot, không phải 7tr
        assertEquals(new BigDecimal("300000"), payment.getMonthlyCharges());
        assertEquals(new BigDecimal("5000000"), payment.getDepositAmount());
        assertEquals(new BigDecimal("10300000"), payment.getTotalAmount());
        assertEquals(expiresAt, payment.getExpiresAt());
    }

    @Test
    @DisplayName("3. Listing thay đổi giá không làm payment thay đổi")
    void paymentAmount_doesNotChange_whenListingPriceChanges() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");

        when(rentalPaymentRepository.findByRentalRequestIdAndType("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.empty());
        when(rentalPaymentRepository.save(any(RentalPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        RentalPayment payment = rentalPaymentService.createInitialPayment(req, Instant.now().plusSeconds(3600));
        assertEquals(new BigDecimal("10300000"), payment.getTotalAmount());

        // Thay đổi giá bài đăng thành 12 triệu
        listing.setPriceAmount(new BigDecimal("12000000"));

        // Số tiền payment vẫn là 10.3 triệu từ snapshot
        assertEquals(new BigDecimal("10300000"), payment.getTotalAmount());
        assertEquals(new BigDecimal("5000000"), payment.getMonthlyRent());
    }

    @Test
    @DisplayName("4 & 5. Renter và Owner được phép xem payment")
    void getInitialPayment_allowedForRenterAndOwner() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");
        RentalPayment payment = RentalPayment.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .renterId("renter-1")
                .ownerId("owner-1")
                .type(RentalPaymentType.INITIAL_PAYMENT)
                .status(RentalPaymentStatus.PENDING)
                .totalAmount(new BigDecimal("10300000"))
                .build();

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));
        when(rentalPaymentRepository.findByRentalRequestIdAndType("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.of(payment));

        RentalPaymentResponse resRenter = rentalPaymentService.getInitialPayment("req-1", "renter-1");
        assertNotNull(resRenter);
        assertEquals("pay-1", resRenter.id());

        RentalPaymentResponse resOwner = rentalPaymentService.getInitialPayment("req-1", "owner-1");
        assertNotNull(resOwner);
        assertEquals("pay-1", resOwner.id());
    }

    @Test
    @DisplayName("6. Người ngoài bị từ chối khi xem payment")
    void getInitialPayment_forbiddenForThirdParty() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));

        AppException ex = assertThrows(AppException.class,
                () -> rentalPaymentService.getInitialPayment("req-1", "stranger-999"));
        assertEquals(ListingErrorCode.RENTAL_PAYMENT_FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("7. Chỉ renter được phép payMock")
    void payMock_forbiddenForOwnerOrStranger() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");
        RentalPayment payment = RentalPayment.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .renterId("renter-1")
                .ownerId("owner-1")
                .type(RentalPaymentType.INITIAL_PAYMENT)
                .status(RentalPaymentStatus.PENDING)
                .totalAmount(new BigDecimal("10300000"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(rentalPaymentRepository.findByRentalRequestIdAndTypeForUpdate("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.of(payment));
        when(rentalRequestRepository.findByIdForUpdate("req-1")).thenReturn(Optional.of(req));

        AppException ex = assertThrows(AppException.class,
                () -> rentalPaymentService.payMock("req-1", "owner-1"));
        assertEquals(ListingErrorCode.RENTAL_PAYMENT_FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("8. Không thanh toán khi request chưa ACCEPTED")
    void payMock_failsWhenRequestNotAccepted() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");
        req.setStatus(RentalRequestStatus.PENDING); // Chưa duyệt

        RentalPayment payment = RentalPayment.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .renterId("renter-1")
                .ownerId("owner-1")
                .type(RentalPaymentType.INITIAL_PAYMENT)
                .status(RentalPaymentStatus.PENDING)
                .totalAmount(new BigDecimal("10300000"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(rentalPaymentRepository.findByRentalRequestIdAndTypeForUpdate("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.of(payment));
        when(rentalRequestRepository.findByIdForUpdate("req-1")).thenReturn(Optional.of(req));

        AppException ex = assertThrows(AppException.class,
                () -> rentalPaymentService.payMock("req-1", "renter-1"));
        assertEquals(ListingErrorCode.INVALID_RENTAL_REQUEST_STATUS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("9. Không thanh toán sau expiresAt")
    void payMock_failsWhenPaymentExpired() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");

        RentalPayment payment = RentalPayment.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .renterId("renter-1")
                .ownerId("owner-1")
                .type(RentalPaymentType.INITIAL_PAYMENT)
                .status(RentalPaymentStatus.PENDING)
                .totalAmount(new BigDecimal("10300000"))
                .expiresAt(Instant.now().minusSeconds(10)) // Đã quá hạn 10s
                .build();

        when(rentalPaymentRepository.findByRentalRequestIdAndTypeForUpdate("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.of(payment));
        when(rentalRequestRepository.findByIdForUpdate("req-1")).thenReturn(Optional.of(req));

        AppException ex = assertThrows(AppException.class,
                () -> rentalPaymentService.payMock("req-1", "renter-1"));
        assertEquals(ListingErrorCode.RENTAL_PAYMENT_EXPIRED.getCode(), ex.getCode());
        assertEquals(RentalPaymentStatus.EXPIRED, payment.getStatus());
    }

    @Test
    @DisplayName("10. payMock retry idempotent không tạo thanh toán thứ hai")
    void payMock_idempotentRetry_returnsExistingPayment() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");
        Instant paidAt = Instant.now().minusSeconds(60);

        RentalPayment payment = RentalPayment.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .renterId("renter-1")
                .ownerId("owner-1")
                .type(RentalPaymentType.INITIAL_PAYMENT)
                .status(RentalPaymentStatus.PAID_MOCK)
                .paidAt(paidAt)
                .totalAmount(new BigDecimal("10300000"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(rentalPaymentRepository.findByRentalRequestIdAndTypeForUpdate("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.of(payment));
        when(rentalRequestRepository.findByIdForUpdate("req-1")).thenReturn(Optional.of(req));

        RentalPaymentResponse res = rentalPaymentService.payMock("req-1", "renter-1");

        assertNotNull(res);
        assertEquals(RentalPaymentStatus.PAID_MOCK, res.status());
        assertEquals(paidAt, res.paidAt());
        verify(rentalPaymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("13. payMock thành công chuyển PAID_MOCK, gia hạn hold và slot xe tới contractDueAt")
    void payMock_success_extendsHoldAndParking() {
        Listing listing = createListing("listing-1", "owner-1", new BigDecimal("5000000"));
        RentalRequest req = createRentalRequest("req-1", listing, "renter-1", "owner-1");

        RentalPayment payment = RentalPayment.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .renterId("renter-1")
                .ownerId("owner-1")
                .type(RentalPaymentType.INITIAL_PAYMENT)
                .status(RentalPaymentStatus.PENDING)
                .totalAmount(new BigDecimal("10300000"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(rentalPaymentRepository.findByRentalRequestIdAndTypeForUpdate("req-1", RentalPaymentType.INITIAL_PAYMENT))
                .thenReturn(Optional.of(payment));
        when(rentalRequestRepository.findByIdForUpdate("req-1")).thenReturn(Optional.of(req));
        when(rentalPaymentRepository.save(any(RentalPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        RentalPaymentResponse res = rentalPaymentService.payMock("req-1", "renter-1");

        assertNotNull(res);
        assertEquals(RentalPaymentStatus.PAID_MOCK, res.status());
        assertNotNull(res.paidAt());
        assertNotNull(res.contractDueAt());
        assertTrue(res.contractDueAt().isAfter(Instant.now().plus(Duration.ofHours(23))));

        // RentalRequest holdExpiresAt được cập nhật tới contractDueAt
        assertEquals(res.contractDueAt(), req.getHoldExpiresAt());
        verify(rentalRequestRepository).save(req);

        // Parking reservations được gia hạn tới contractDueAt
        verify(parkingReservationService).extendHeldReservations("req-1", res.contractDueAt());
    }
}
