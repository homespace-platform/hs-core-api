package com.hs.listing.service;

import com.hs.listing.dto.request.CreateBranchChargeRequest;
import com.hs.listing.model.BranchCharge;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchChargeMappingHelperTest {

    @Test
    void testToListingCharge_copiesAllFields() {
        BranchCharge bc = BranchCharge.builder()
                .id("bc-1")
                .chargeType(ChargeType.WATER)
                .billingMethod(BillingMethod.PER_M3)
                .amount(new BigDecimal("25000"))
                .currency("VND")
                .unit("m³")
                .includedInRent(false)
                .customName("Nước sinh hoạt")
                .description("Tính theo đồng hồ riêng")
                .sortOrder(2)
                .build();

        Listing listing = new Listing();
        listing.setId("listing-1");

        ListingCharge lc = BranchChargeMappingHelper.toListingCharge(bc, listing);

        assertNotNull(lc);
        assertNotNull(lc.getId());
        assertNotEquals("bc-1", lc.getId()); // must generate its own ID for listing charge
        assertEquals(listing, lc.getListing());
        assertEquals(ChargeType.WATER, lc.getChargeType());
        assertEquals(BillingMethod.PER_M3, lc.getBillingMethod());
        assertEquals(new BigDecimal("25000"), lc.getAmount());
        assertEquals("VND", lc.getCurrency());
        assertEquals("m³", lc.getUnit());
        assertFalse(lc.isIncludedInRent());
        assertEquals("Nước sinh hoạt", lc.getCustomName());
        assertEquals("Tính theo đồng hồ riêng", lc.getDescription());
        assertEquals(2, lc.getSortOrder());
    }

    @Test
    void testFindMissingCharges_detectsIncompleteCharges() {
        List<BranchCharge> charges = new ArrayList<>();
        charges.add(BranchCharge.builder().chargeType(ChargeType.ELECTRICITY).billingMethod(BillingMethod.PER_KWH).build());
        charges.add(BranchCharge.builder().chargeType(ChargeType.WATER).billingMethod(BillingMethod.PER_M3).build());

        List<String> missing = BranchChargeMappingHelper.findMissingCharges(ListingCategory.APARTMENT, charges);
        assertFalse(missing.isEmpty());
        assertTrue(missing.contains("Phí quản lý tòa nhà"));
        assertTrue(missing.contains("Internet / WiFi"));
        assertTrue(missing.contains("Phí rác & Vệ sinh"));
        assertTrue(missing.contains("Phí gửi xe máy"));
        assertTrue(missing.contains("Phí gửi ô tô"));
        assertFalse(missing.contains("Tiền điện"));
        assertFalse(missing.contains("Tiền nước sinh hoạt"));

        assertFalse(BranchChargeMappingHelper.isChargesComplete(ListingCategory.APARTMENT, charges));
    }

    @Test
    void testIsChargesComplete_returnsTrueWhenAllMandatoryPresent() {
        List<BranchCharge> charges = new ArrayList<>();
        charges.add(BranchCharge.builder().chargeType(ChargeType.ELECTRICITY).billingMethod(BillingMethod.PER_KWH).build());
        charges.add(BranchCharge.builder().chargeType(ChargeType.WATER).billingMethod(BillingMethod.PER_M3).build());
        charges.add(BranchCharge.builder().chargeType(ChargeType.INTERNET).billingMethod(BillingMethod.INCLUDED).build());
        charges.add(BranchCharge.builder().chargeType(ChargeType.SERVICE_OR_GARBAGE).billingMethod(BillingMethod.PER_MONTH).build());
        charges.add(BranchCharge.builder().chargeType(ChargeType.MOTORBIKE_PARKING).billingMethod(BillingMethod.PER_VEHICLE_MONTH).build());
        charges.add(BranchCharge.builder().chargeType(ChargeType.CAR_PARKING).billingMethod(BillingMethod.NOT_APPLICABLE).build());

        // For ROOM (does not require MANAGEMENT)
        assertTrue(BranchChargeMappingHelper.isChargesComplete(ListingCategory.ROOM, charges));

        // For APARTMENT (still requires MANAGEMENT)
        assertFalse(BranchChargeMappingHelper.isChargesComplete(ListingCategory.APARTMENT, charges));

        // Add MANAGEMENT
        charges.add(BranchCharge.builder().chargeType(ChargeType.MANAGEMENT).billingMethod(BillingMethod.PER_M2_MONTH).build());
        assertTrue(BranchChargeMappingHelper.isChargesComplete(ListingCategory.APARTMENT, charges));
    }

    @Test
    void testValidateBranchCharges_rejectsNegativeAmount() {
        CreateBranchChargeRequest req = CreateBranchChargeRequest.builder()
                .chargeType(ChargeType.ELECTRICITY)
                .billingMethod(BillingMethod.PER_KWH)
                .amount(new BigDecimal("-1000"))
                .build();

        assertThrows(Exception.class, () ->
                BranchChargeMappingHelper.validateBranchCharges(List.of(req), 10, 5)
        );
    }

    @Test
    void testValidateBranchCharges_rejectsParkingWhenCapacityZero() {
        CreateBranchChargeRequest req = CreateBranchChargeRequest.builder()
                .chargeType(ChargeType.MOTORBIKE_PARKING)
                .billingMethod(BillingMethod.PER_VEHICLE_MONTH)
                .amount(new BigDecimal("100000"))
                .build();

        assertThrows(Exception.class, () ->
                BranchChargeMappingHelper.validateBranchCharges(List.of(req), 0, 5)
        );
    }

    @Test
    void testValidateBranchCharges_rejectsCapacityWhenParkingNotApplicable() {
        CreateBranchChargeRequest motoReq = CreateBranchChargeRequest.builder()
                .chargeType(ChargeType.MOTORBIKE_PARKING)
                .billingMethod(BillingMethod.NOT_APPLICABLE)
                .build();

        assertThrows(Exception.class, () ->
                BranchChargeMappingHelper.validateBranchCharges(List.of(motoReq), 10, 0)
        );

        CreateBranchChargeRequest carReq = CreateBranchChargeRequest.builder()
                .chargeType(ChargeType.CAR_PARKING)
                .billingMethod(BillingMethod.NOT_APPLICABLE)
                .build();

        assertThrows(Exception.class, () ->
                BranchChargeMappingHelper.validateBranchCharges(List.of(carReq), 0, 5)
        );
    }
}
