package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.estimate.ExcludedChargeItem;
import com.hs.listing.dto.estimate.PredictableChargeItem;
import com.hs.listing.dto.estimate.RentalEstimateResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingApartmentDetail;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.ListingHouseDetail;
import com.hs.listing.model.ListingRoomDetail;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.DepositType;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import com.hs.listing.model.constant.ListingEnums.ParkingPolicy;
import com.hs.listing.model.constant.PriceUnit;
import com.hs.listing.model.constant.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class RentalCostCalculatorTest {

    private ParkingReservationService parkingReservationService;
    private RentalCostCalculator calculator;

    @BeforeEach
    void setUp() {
        parkingReservationService = Mockito.mock(ParkingReservationService.class);
        when(parkingReservationService.getAvailability(any(), any(), any(), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(5, 0, 5));
        calculator = new RentalCostCalculator(parkingReservationService);
    }

    private Listing createBasicListing(ListingCategory category, PriceUnit priceUnit, BigDecimal priceAmount) {
        return Listing.builder()
                .id("test-listing-1")
                .category(category)
                .priceUnit(priceUnit)
                .priceAmount(priceAmount)
                .depositType(DepositType.MONTH_COUNT)
                .depositMonths(1)
                .maxMotorbikeCount(5)
                .maxCarCount(2)
                .charges(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("1. MONTH & ROOM_MONTH giữ nguyên giá thuê")
    void testMonthAndRoomMonthKeepSamePrice() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        when(parkingReservationService.getAvailability(any(), any(), eq(VehicleType.MOTORBIKE), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(5, 0, 5));
        when(parkingReservationService.getAvailability(any(), any(), eq(VehicleType.CAR), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(2, 0, 2));

        // MONTH
        Listing listingMonth = createBasicListing(ListingCategory.APARTMENT, PriceUnit.MONTH, new BigDecimal("10000000"));
        RentalEstimateResponse res1 = calculator.calculate(listingMonth, moveIn, 12, 4, 0, 0, null);
        assertEquals(new BigDecimal("10000000"), res1.effectiveMonthlyRent());

        // ROOM_MONTH
        Listing listingRoom = createBasicListing(ListingCategory.ROOM, PriceUnit.ROOM_MONTH, new BigDecimal("3500000"));
        RentalEstimateResponse res2 = calculator.calculate(listingRoom, moveIn, 6, 2, 0, 0, null);
        assertEquals(new BigDecimal("3500000"), res2.effectiveMonthlyRent());
    }

    @Test
    @DisplayName("2. PERSON_MONTH nhân occupantCount")
    void testPersonMonthMultipliesOccupantCount() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        when(parkingReservationService.getAvailability(any(), any(), any(), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(5, 0, 5));

        Listing listing = createBasicListing(ListingCategory.ROOM, PriceUnit.PERSON_MONTH, new BigDecimal("1500000"));
        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 12, 3, 0, 0, null);
        assertEquals(new BigDecimal("4500000"), res.effectiveMonthlyRent());
    }

    @Test
    @DisplayName("3. Deposit MONTH_COUNT dùng effectiveMonthlyRent (bao gồm cả khi PERSON_MONTH nhân occupantCount)")
    void testDepositMonthCountUsesEffectiveMonthlyRent() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        when(parkingReservationService.getAvailability(any(), any(), any(), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(5, 0, 5));

        Listing listing = createBasicListing(ListingCategory.ROOM, PriceUnit.PERSON_MONTH, new BigDecimal("2000000"));
        listing.setDepositMonths(2);

        // 3 người -> effectiveMonthlyRent = 6,000,000 -> cọc 2 tháng = 12,000,000
        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 12, 3, 0, 0, null);
        assertEquals(new BigDecimal("6000000"), res.effectiveMonthlyRent());
        assertEquals(new BigDecimal("12000000"), res.depositAmount());
    }

    @Test
    @DisplayName("4. PER_PERSON_MONTH nhân occupantCount")
    void testPerPersonMonthMultipliesOccupants() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        Listing listing = createBasicListing(ListingCategory.ROOM, PriceUnit.MONTH, new BigDecimal("4000000"));
        ListingCharge waterCharge = ListingCharge.builder()
                .chargeType(ChargeType.WATER)
                .billingMethod(BillingMethod.PER_PERSON_MONTH)
                .amount(new BigDecimal("100000"))
                .build();
        listing.getCharges().add(waterCharge);

        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 12, 3, 0, 0, null);
        PredictableChargeItem waterItem = res.predictableCharges().stream()
                .filter(c -> "WATER".equals(c.chargeType()))
                .findFirst().orElseThrow();

        assertEquals(new BigDecimal("300000"), waterItem.amount());
        assertEquals(3, waterItem.quantity());
    }

    @Test
    @DisplayName("5. PER_VEHICLE_MONTH dùng đúng loại xe máy và ô tô")
    void testPerVehicleMonthUsesCorrectVehicleCounts() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        Listing listing = createBasicListing(ListingCategory.APARTMENT, PriceUnit.MONTH, new BigDecimal("8000000"));

        ListingCharge motorbikeCharge = ListingCharge.builder()
                .chargeType(ChargeType.MOTORBIKE_PARKING)
                .billingMethod(BillingMethod.PER_VEHICLE_MONTH)
                .amount(new BigDecimal("120000"))
                .build();

        ListingCharge carCharge = ListingCharge.builder()
                .chargeType(ChargeType.CAR_PARKING)
                .billingMethod(BillingMethod.PER_VEHICLE_MONTH)
                .amount(new BigDecimal("1500000"))
                .build();

        listing.getCharges().add(motorbikeCharge);
        listing.getCharges().add(carCharge);

        when(parkingReservationService.getAvailability(any(), any(), eq(VehicleType.MOTORBIKE), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(10, 2, 8));
        when(parkingReservationService.getAvailability(any(), any(), eq(VehicleType.CAR), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(5, 1, 4));

        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 6, 2, 2, 1, null);

        PredictableChargeItem mbItem = res.predictableCharges().stream()
                .filter(c -> "MOTORBIKE_PARKING".equals(c.chargeType()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("240000"), mbItem.amount());

        PredictableChargeItem carItem = res.predictableCharges().stream()
                .filter(c -> "CAR_PARKING".equals(c.chargeType()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("1500000"), carItem.amount());
    }

    @Test
    @DisplayName("6. PER_MONTH được cộng vào chi phí")
    void testPerMonthIsAdded() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        Listing listing = createBasicListing(ListingCategory.APARTMENT, PriceUnit.MONTH, new BigDecimal("5000000"));

        ListingCharge internet = ListingCharge.builder()
                .chargeType(ChargeType.INTERNET)
                .billingMethod(BillingMethod.PER_MONTH)
                .amount(new BigDecimal("250000"))
                .build();
        listing.getCharges().add(internet);

        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 6, 1, 0, 0, null);
        PredictableChargeItem item = res.predictableCharges().stream()
                .filter(c -> "INTERNET".equals(c.chargeType()))
                .findFirst().orElseThrow();

        assertEquals(new BigDecimal("250000"), item.amount());
    }

    @Test
    @DisplayName("7. INCLUDED và FREE bằng 0 đồng và vẫn xuất hiện trong breakdown")
    void testIncludedAndFreeAreZeroAndPresent() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        Listing listing = createBasicListing(ListingCategory.APARTMENT, PriceUnit.MONTH, new BigDecimal("6000000"));

        ListingCharge management = ListingCharge.builder()
                .chargeType(ChargeType.MANAGEMENT)
                .billingMethod(BillingMethod.INCLUDED)
                .amount(new BigDecimal("500000"))
                .build();

        ListingCharge garbage = ListingCharge.builder()
                .chargeType(ChargeType.SERVICE_OR_GARBAGE)
                .billingMethod(BillingMethod.FREE)
                .amount(BigDecimal.ZERO)
                .build();

        listing.getCharges().add(management);
        listing.getCharges().add(garbage);

        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 12, 1, 0, 0, null);

        PredictableChargeItem mgmtItem = res.predictableCharges().stream()
                .filter(c -> "MANAGEMENT".equals(c.chargeType()))
                .findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO, mgmtItem.amount());
        assertTrue(mgmtItem.includedInRent());

        PredictableChargeItem gbItem = res.predictableCharges().stream()
                .filter(c -> "SERVICE_OR_GARBAGE".equals(c.chargeType()))
                .findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO, gbItem.amount());
        assertFalse(gbItem.includedInRent());
    }

    @Test
    @DisplayName("8. PER_KWH, PER_M3, STATE_WATER_RATE, PER_HOUR, NEGOTIABLE, CUSTOM, NOT_APPLICABLE bị loại khỏi total và xuất hiện trong excluded")
    void testNonPredictableChargesAreExcludedWithReasons() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        Listing listing = createBasicListing(ListingCategory.ROOM, PriceUnit.MONTH, new BigDecimal("3000000"));

        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.ELECTRICITY).billingMethod(BillingMethod.PER_KWH).amount(new BigDecimal("3500")).build());
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.WATER).billingMethod(BillingMethod.PER_M3).amount(new BigDecimal("25000")).build());
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.WATER).billingMethod(BillingMethod.STATE_WATER_RATE).build());
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.OVERTIME_AIR_CONDITIONING).billingMethod(BillingMethod.PER_HOUR).amount(new BigDecimal("50000")).build());
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.OTHER).billingMethod(BillingMethod.NEGOTIABLE).build());
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.OTHER).billingMethod(BillingMethod.CUSTOM).build());
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.INTERNET).billingMethod(BillingMethod.NOT_APPLICABLE).build());

        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 12, 1, 0, 0, null);

        // Không có khoản nào trong số này lọt vào predictableCharges
        assertEquals(0, res.predictableCharges().stream()
                .filter(c -> List.of("ELECTRICITY", "OVERTIME_AIR_CONDITIONING").contains(c.chargeType()))
                .count());

        // Phải có 7 khoản trong excludedCharges
        assertEquals(7, res.excludedCharges().size());
        for (ExcludedChargeItem item : res.excludedCharges()) {
            assertNotNull(item.reason());
            assertFalse(item.reason().isBlank());
        }
    }

    @Test
    @DisplayName("9. PER_M2_MONTH không được cộng vào tổng cho HOUSE/APARTMENT/ROOM")
    void testPerM2MonthExcludedForResidential() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        PriceUnit perM2Unit = null;
        for (PriceUnit u : PriceUnit.values()) {
            if (u.name().contains("M2")) {
                perM2Unit = u;
                break;
            }
        }
        if (perM2Unit == null) {
            return;
        }

        Listing listing = createBasicListing(ListingCategory.APARTMENT, perM2Unit, new BigDecimal("200000"));
        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 12, 2, 0, 0, null);

        // Effective rent phải là 0 vì không tính trước
        assertEquals(BigDecimal.ZERO, res.effectiveMonthlyRent());
        assertTrue(res.excludedCharges().stream().anyMatch(e -> "RENT".equals(e.chargeType())));
    }

    @Test
    @DisplayName("10. estimatedMonthlyTotal, estimatedInitialTotal, estimatedLeaseTotal tính chính xác")
    void testTotalsCalculationAccuracy() {
        LocalDate moveIn = LocalDate.of(2026, 10, 1);
        Listing listing = createBasicListing(ListingCategory.APARTMENT, PriceUnit.MONTH, new BigDecimal("10000000"));
        listing.setDepositMonths(2); // Cọc 2 tháng = 20,000,000

        // Phí cố định 1: Internet 300,000
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.INTERNET).billingMethod(BillingMethod.PER_MONTH).amount(new BigDecimal("300000")).build());
        // Phí cố định 2: Nước theo 2 người x 100,000 = 200,000
        listing.getCharges().add(ListingCharge.builder().chargeType(ChargeType.WATER).billingMethod(BillingMethod.PER_PERSON_MONTH).amount(new BigDecimal("100000")).build());

        RentalEstimateResponse res = calculator.calculate(listing, moveIn, 12, 2, 0, 0, null);

        // effectiveMonthlyRent = 10,000,000
        assertEquals(new BigDecimal("10000000"), res.effectiveMonthlyRent());
        // predictableMonthlyChargesTotal = 300,000 + 200,000 = 500,000
        assertEquals(new BigDecimal("500000"), res.predictableMonthlyChargesTotal());
        // estimatedMonthlyTotal = 10,000,000 + 500,000 = 10,500,000
        assertEquals(new BigDecimal("10500000"), res.estimatedMonthlyTotal());
        // depositAmount = 20,000,000
        assertEquals(new BigDecimal("20000000"), res.depositAmount());
        // estimatedInitialTotal = 10,500,000 + 20,000,000 = 30,500,000
        assertEquals(new BigDecimal("30500000"), res.estimatedInitialTotal());
        // estimatedLeaseTotal = 10,500,000 * 12 + 20,000,000 = 126,000,000 + 20,000,000 = 146,000,000
        assertEquals(new BigDecimal("146000000"), res.estimatedLeaseTotal());
    }
}
