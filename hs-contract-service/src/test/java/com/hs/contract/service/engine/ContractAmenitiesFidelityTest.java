package com.hs.contract.service.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.dto.snapshot.ListingSnapshotDto;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.service.ListingAmenitySnapshots;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ContractAmenitiesFidelityTest {
    private static final List<String> NAMES = List.of("WiFi", "Máy lạnh", "Máy nước nóng", "Tủ lạnh", "Máy giặt", "Ban công đón gió");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @EnumSource(value=ListingCategory.class,names={"HOUSE","APARTMENT","ROOM"})
    void frozenAndLivePathsPreserveSixListingNamesAndNeverInferParking(ListingCategory category) throws Exception {
        String[] codes = {"WIFI","AIR_CONDITIONER","WATER_HEATER","REFRIGERATOR","WASHING_MACHINE"};
        var amenities = new HashSet<Amenity>();
        for (int i=0;i<codes.length;i++) {
            var item = new Amenity(); item.setCode(codes[i]); item.setName(NAMES.get(i)); item.setSortOrder(i);
            amenities.add(item);
        }
        var custom = new ListingCustomAmenity(); custom.setName(NAMES.getLast());
        var branchOnly = new Amenity(); branchOnly.setCode("POOL"); branchOnly.setName("Hồ bơi chưa chọn");
        var listing = Listing.builder().id("01316977-2d31-6aef-24f5-ee964ff84314").category(category)
                .amenities(amenities).customAmenities(List.of(custom))
                .branch(PropertyBranch.builder().buildingAmenities(Set.of(branchOnly)).build()).build();
        var request = RentalRequest.builder().occupantCount(1).motorbikeCount(4).carCount(1)
                .moveInDate(LocalDate.of(2026,10,15)).leaseMonths(3).build();
        var builder = new ContractDataBuilder(mock(UserRepository.class),mock(AddressRepository.class),mapper);
        var captured = ListingSnapshotDto.builder().category(category).amenities(ListingAmenitySnapshots.resolve(listing,listing.getBranch()))
                .customAmenities(List.of(custom.getName())).build();
        for (boolean frozen:List.of(false,true)) {
            if (frozen) {
                request.setListingSnapshot(mapper.writeValueAsString(captured));
                listing.setAmenities(Set.of(branchOnly)); listing.setCustomAmenities(List.of());
            }
            var actual = builder.build(request,listing).getAmenities();
            assertEquals(NAMES,actual.stream().map(r -> (String)r.get("name")).toList());
            actual.forEach(r -> assertEquals(Set.of("index","code","name","sourceType"),r.keySet()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"HomeSpace_01_Hop_Dong_Thue_Nha_Nguyen_Can.docx", "HomeSpace_02_Hop_Dong_Thue_Can_Ho_Chung_Cu.docx", "HomeSpace_03_Hop_Dong_Thue_Phong_Tro.docx"})
    void realTemplatesRenderOnlyTwoAmenityColumnsAndExactNames(String filename) throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("hs-infra/documents/contract-template"))) root=root.getParent();
        assertNotNull(root,"Cannot locate the real contract templates");
        var renderer = new ContractRenderService(mapper);
        for (boolean empty:List.of(false,true)) {
            var rows=new ArrayList<Map<String,Object>>();
            if (!empty) for (var name:NAMES) rows.add(Map.of("name",name));
            var property=Map.<String,Object>of("features",List.of(
                    Map.of("featureName","Tình trạng nội thất","featureValue","FULLY_FURNISHED"),
                    Map.of("featureName","Loại lối đi","featureValue","ĐƯỜNG NỘI BỘ")));
            var tables=renderer.buildDataModelFromSnapshots(null,null,property,null,null,List.of(),List.of(),null,null,rows,null,"",3,1,"HD-QA",LocalDate.of(2026,10,15),"Hồ Chí Minh");
            var model=renderer.buildDummyDataModel(); model.put("amenitiesTable",tables.get("amenitiesTable"));
            model.put("propertyFeaturesTable",tables.get("propertyFeaturesTable"));
            byte[] bytes;
            try (var input=Files.newInputStream(root.resolve("hs-infra/documents/contract-template").resolve(filename))) {
                bytes=renderer.renderDocx(input,model);
            }
            try (var document=new XWPFDocument(new ByteArrayInputStream(bytes))) {
                var table=document.getTables().stream().filter(t -> t.getRow(0).getTableCells().size()>=2 && t.getRow(0).getCell(1).getText().equals("Tên tiện ích")).findFirst().orElseThrow();
                table.getRows().forEach(row -> assertEquals(2,row.getTableCells().size()));
                assertFalse(table.getText().contains("Phạm vi")); assertFalse(table.getText().contains("Chi phí"));
                assertFalse(table.getText().contains("Điều kiện")); assertFalse(table.getText().contains("Chỗ để xe"));
                if (!empty) assertEquals(NAMES,table.getRows().stream().skip(1).map(r -> r.getCell(1).getText()).toList());
                else assertEquals(2,table.getRows().size());
                assertTrue(document.getParagraphs().stream().noneMatch(p -> p.getText().startsWith("4.3. Đối với tiện ích")));
                var features=document.getTables().stream().filter(t -> t.getRow(0).getCell(0).getText().equals("Đặc điểm")).findFirst().orElseThrow();
                assertTrue(features.getText().contains("Đầy đủ nội thất")); assertTrue(features.getText().contains("Đường nội bộ"));
                assertFalse(features.getText().contains("FULLY_FURNISHED")); assertFalse(features.getText().contains("ĐƯỜNG NỘI BỘ"));
                assertTrue(property.toString().contains("FULLY_FURNISHED"),"Rendering must not rewrite persisted snapshots");
            }
            var output=Path.of("target","amenities-qa"); Files.createDirectories(output);
            Files.write(output.resolve((empty?"empty-":"six-")+filename),bytes);
        }
    }
}
