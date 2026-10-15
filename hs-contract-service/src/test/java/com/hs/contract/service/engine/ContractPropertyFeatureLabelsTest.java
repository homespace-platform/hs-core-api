package com.hs.contract.service.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.dto.snapshot.ListingSnapshotDto;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ContractPropertyFeatureLabelsTest {
    @ParameterizedTest
    @CsvSource(value={
        "Tình trạng nội thất|UNFURNISHED|Không nội thất",
        "Tình trạng nội thất|BASIC|Nội thất cơ bản",
        "Tình trạng nội thất|PARTIALLY_FURNISHED|Nội thất một phần",
        "Tình trạng nội thất|FULLY_FURNISHED|Đầy đủ nội thất",
        "Tình trạng nội thất|LUXURY|Nội thất cao cấp",
        "Loại lối đi|ĐƯỜNG NỘI BỘ|Đường nội bộ",
        "Loại lối đi|HẺM XE HƠI|Hẻm xe hơi",
        "Lối đi|PRIVATE|Riêng biệt", "Lối đi|SHARED|Dùng chung",
        "Nhà vệ sinh|PRIVATE|Riêng", "Nhà vệ sinh|SHARED|Dùng chung",
        "Khu vực bếp|PRIVATE|Riêng", "Khu vực bếp|SHARED|Dùng chung", "Khu vực bếp|NONE|Không có",
        "Ban công|PRIVATE|Riêng", "Ban công|SHARED|Dùng chung", "Ban công|NONE|Không có",
        "Đồng hồ điện|PRIVATE|Riêng", "Đồng hồ điện|SHARED|Dùng chung",
        "Đồng hồ nước|PRIVATE|Riêng", "Đồng hồ nước|SHARED|Dùng chung",
        "Giờ giấc sinh hoạt|FLEXIBLE|Tự do giờ giấc", "Giờ giấc sinh hoạt|CURFEW|Có giờ đóng cửa",
        "Chính sách gửi xe|NONE|Không có chỗ gửi xe", "Chính sách gửi xe|FREE|Miễn phí", "Chính sách gửi xe|PAID|Có thu phí",
        "Hướng cửa chính|SOUTH_EAST|Đông Nam", "Hướng ban công|NORTHEAST|Đông Bắc",
        "Tình trạng pháp lý|PINK_BOOK|Sổ hồng", "Tình trạng pháp lý|CONTRACT|Hợp đồng mua bán"
    },delimiter='|')
    void knownValuesHaveReadableVietnameseLabels(String field,String raw,String expected) {
        assertEquals(expected,ContractPropertyFeatureLabels.value(field,raw));
    }

    @Test void fieldAwareMappingPreservesManualDescriptionsAndUnknownValues() {
        assertEquals("FULLY_FURNISHED",ContractPropertyFeatureLabels.value("Tên dự án","FULLY_FURNISHED"));
        assertEquals("Hẻm ABC, gần UBND - PCCC",ContractPropertyFeatureLabels.value("Lối đi","Hẻm ABC, gần UBND - PCCC"));
        assertEquals("CUSTOM_STATUS",ContractPropertyFeatureLabels.value("Tình trạng nội thất","CUSTOM_STATUS"));
        assertEquals("Đầy đủ nội thất",ContractPropertyFeatureLabels.value("Tình trạng nội thất"," fully_furnished "));
        assertEquals("",ContractPropertyFeatureLabels.value("Tình trạng nội thất",null));
    }

    @ParameterizedTest
    @EnumSource(value=ListingCategory.class,names={"HOUSE","APARTMENT","ROOM"})
    void newPropertySnapshotsUseLabelsWithoutChangingFrozenListingCodes(ListingCategory category) throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var captured=ListingSnapshotDto.builder().category(category);
        switch (category) {
            case HOUSE -> captured.houseDetail(ListingSnapshotDto.HouseDetailSnapshot.builder().furnishingStatus("FULLY_FURNISHED").accessType("ĐƯỜNG NỘI BỘ").build());
            case APARTMENT -> captured.apartmentDetail(ListingSnapshotDto.ApartmentDetailSnapshot.builder().furnishingStatus("FULLY_FURNISHED").mainDoorDirection("EAST").build());
            case ROOM -> captured.roomDetail(ListingSnapshotDto.RoomDetailSnapshot.builder().furnishingStatus("FULLY_FURNISHED").accessType("SHARED").parkingPolicy("PAID").build());
            default -> throw new AssertionError(category);
        }
        String json=mapper.writeValueAsString(captured.build());
        var request=RentalRequest.builder().listingSnapshot(json).occupantCount(1).build();
        var data=new ContractDataBuilder(mock(UserRepository.class),mock(AddressRepository.class),mapper).build(request,null);
        var features=(List<?>)data.getProperty().get("features");
        assertTrue(mapper.writeValueAsString(features).contains("Đầy đủ nội thất"));
        assertFalse(mapper.writeValueAsString(features).contains("FULLY_FURNISHED"));
        if (category==ListingCategory.HOUSE) assertTrue(mapper.writeValueAsString(features).contains("Đường nội bộ"));
        assertEquals(json,request.getListingSnapshot());
        assertTrue(json.contains("FULLY_FURNISHED"));
    }
}
