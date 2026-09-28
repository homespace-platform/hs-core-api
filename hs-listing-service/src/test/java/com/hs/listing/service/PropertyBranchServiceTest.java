package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.dto.request.CreateBranchChargeRequest;
import com.hs.listing.dto.request.CreatePropertyBranchRequest;
import com.hs.listing.dto.response.PropertyBranchResponse;
import com.hs.listing.model.BranchCharge;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import com.hs.listing.repository.AmenityRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PropertyBranchServiceTest {

    private PropertyBranchRepository branchRepository;
    private AmenityRepository amenityRepository;
    private ListingRepository listingRepository;
    private ParkingReservationService parkingReservationService;
    private StorageObjectRepository storageObjectRepository;
    private StorageProperties storageProperties;

    private PropertyBranchService service;

    @BeforeEach
    void setUp() {
        branchRepository = mock(PropertyBranchRepository.class);
        amenityRepository = mock(AmenityRepository.class);
        listingRepository = mock(ListingRepository.class);
        parkingReservationService = mock(ParkingReservationService.class);
        storageObjectRepository = mock(StorageObjectRepository.class);
        storageProperties = mock(StorageProperties.class);

        when(storageProperties.region()).thenReturn("ap-southeast-1");

        service = new PropertyBranchService(
                branchRepository,
                amenityRepository,
                listingRepository,
                parkingReservationService,
                storageObjectRepository,
                storageProperties
        );
    }

    @Test
    void testCreateBranch_successWithCoverImageAndCharges() {
        String ownerId = "owner-1";
        String storageId = "storage-1";

        StorageObject mockImg = StorageObject.builder()
                .id(storageId)
                .ownerId(ownerId)
                .status(StorageStatus.READY)
                .purpose(StoragePurpose.BRANCH_COVER_IMAGE)
                .contentType("image/jpeg")
                .visibility(StorageVisibility.PUBLIC)
                .bucketName("homespace-bucket")
                .objectKey("branch_cover_image/owner-1/storage-1.jpg")
                .build();
        mockImg.setActive(true);
        when(storageObjectRepository.findById(storageId)).thenReturn(Optional.of(mockImg));

        when(branchRepository.save(any(PropertyBranch.class))).thenAnswer(inv -> {
            PropertyBranch b = inv.getArgument(0);
            return b;
        });

        List<CreateBranchChargeRequest> charges = List.of(
                CreateBranchChargeRequest.builder().chargeType(ChargeType.ELECTRICITY).billingMethod(BillingMethod.PER_KWH).amount(new BigDecimal("3500")).unit("kWh").build(),
                CreateBranchChargeRequest.builder().chargeType(ChargeType.WATER).billingMethod(BillingMethod.PER_M3).amount(new BigDecimal("25000")).unit("m³").build(),
                CreateBranchChargeRequest.builder().chargeType(ChargeType.INTERNET).billingMethod(BillingMethod.INCLUDED).includedInRent(true).build(),
                CreateBranchChargeRequest.builder().chargeType(ChargeType.SERVICE_OR_GARBAGE).billingMethod(BillingMethod.PER_MONTH).amount(new BigDecimal("50000")).build(),
                CreateBranchChargeRequest.builder().chargeType(ChargeType.MOTORBIKE_PARKING).billingMethod(BillingMethod.PER_VEHICLE_MONTH).amount(new BigDecimal("120000")).build(),
                CreateBranchChargeRequest.builder().chargeType(ChargeType.CAR_PARKING).billingMethod(BillingMethod.NOT_APPLICABLE).build(),
                CreateBranchChargeRequest.builder().chargeType(ChargeType.OTHER).billingMethod(BillingMethod.PER_MONTH).amount(new BigDecimal("100000")).customName("Hồ bơi").build()
        );

        CreatePropertyBranchRequest request = CreatePropertyBranchRequest.builder()
                .name("Chi nhánh Thảo Điền")
                .category(ListingCategory.ROOM)
                .fullAddress("123 Nguyễn Văn Hưởng, Thảo Điền, TP. Thủ Đức")
                .motorbikeParkingCapacity(20)
                .carParkingCapacity(0)
                .coverImageId(storageId)
                .defaultCharges(charges)
                .build();

        PropertyBranchResponse resp = service.createBranch(ownerId, request);

        assertNotNull(resp);
        assertEquals("Chi nhánh Thảo Điền", resp.getName());
        assertEquals(storageId, resp.getCoverImageId());
        assertTrue(resp.getCoverImageUrl().contains("homespace-bucket"));
        assertTrue(resp.getIsComplete());
        assertEquals(7, resp.getDefaultCharges().size());
        assertEquals("PROPERTY_BRANCH", mockImg.getReferenceType());
    }

    @Test
    void testCreateBranch_failsWhenCoverImageOwnedByAnotherUser() {
        String ownerId = "owner-1";
        String storageId = "storage-evil";

        StorageObject mockImg = StorageObject.builder()
                .id(storageId)
                .ownerId("another-user")
                .status(StorageStatus.READY)
                .purpose(StoragePurpose.BRANCH_COVER_IMAGE)
                .contentType("image/jpeg")
                .build();
        mockImg.setActive(true);
        when(storageObjectRepository.findById(storageId)).thenReturn(Optional.of(mockImg));

        CreatePropertyBranchRequest request = CreatePropertyBranchRequest.builder()
                .name("Chi nhánh Test")
                .category(ListingCategory.ROOM)
                .fullAddress("123 Test")
                .coverImageId(storageId)
                .build();

        AppException ex = assertThrows(AppException.class, () -> service.createBranch(ownerId, request));
        assertEquals(403, ex.getCode());
    }

    @Test
    void testUpdateBranch_syncsChargesToActiveListings() {
        String ownerId = "owner-1";
        String branchId = "branch-1";

        PropertyBranch branch = PropertyBranch.builder()
                .id(branchId)
                .ownerId(ownerId)
                .name("Tòa nhà Sunrise")
                .category(ListingCategory.ROOM)
                .motorbikeParkingCapacity(10)
                .carParkingCapacity(5)
                .defaultCharges(new ArrayList<>())
                .build();

        when(branchRepository.findByIdAndOwnerIdAndActiveTrue(branchId, ownerId)).thenReturn(Optional.of(branch));
        when(branchRepository.save(any(PropertyBranch.class))).thenAnswer(inv -> inv.getArgument(0));

        // Listing 1 belongs to branch and has old charges
        Listing l1 = new Listing();
        l1.setId("listing-1");
        l1.setBranchId(branchId);
        l1.setActive(true);
        l1.getCharges().add(ListingCharge.builder().chargeType(ChargeType.ELECTRICITY).amount(new BigDecimal("3000")).build());

        // Listing 2 belongs to branch
        Listing l2 = new Listing();
        l2.setId("listing-2");
        l2.setBranchId(branchId);
        l2.setActive(true);

        when(listingRepository.findAllByBranchIdAndActiveTrue(branchId)).thenReturn(List.of(l1, l2));

        // Update request with new electricity fee = 4000
        List<CreateBranchChargeRequest> newCharges = List.of(
                CreateBranchChargeRequest.builder().chargeType(ChargeType.ELECTRICITY).billingMethod(BillingMethod.PER_KWH).amount(new BigDecimal("4000")).unit("kWh").build(),
                CreateBranchChargeRequest.builder().chargeType(ChargeType.WATER).billingMethod(BillingMethod.PER_M3).amount(new BigDecimal("30000")).unit("m³").build()
        );

        CreatePropertyBranchRequest updateReq = CreatePropertyBranchRequest.builder()
                .name("Tòa nhà Sunrise Cập Nhật")
                .category(ListingCategory.ROOM)
                .fullAddress("456 Lê Văn Việt")
                .motorbikeParkingCapacity(10)
                .carParkingCapacity(5)
                .defaultCharges(newCharges)
                .build();

        PropertyBranchResponse resp = service.updateBranch(branchId, ownerId, updateReq);

        assertNotNull(resp);
        assertEquals("Tòa nhà Sunrise Cập Nhật", resp.getName());

        // Verify that l1 and l2 charges were cleared and replaced with the 2 new charges
        assertEquals(2, l1.getCharges().size());
        assertEquals(new BigDecimal("4000"), l1.getCharges().get(0).getAmount());
        assertEquals(2, l2.getCharges().size());
        assertEquals(new BigDecimal("4000"), l2.getCharges().get(0).getAmount());

        verify(listingRepository).saveAll(List.of(l1, l2));
    }
}
