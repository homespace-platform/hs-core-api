package com.hs.listing.dto.request;

import com.hs.listing.model.constant.ListingCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePropertyBranchRequest {
    @NotBlank(message = "Tên chi nhánh không được để trống")
    private String name;

    private String code;

    @NotNull(message = "Loại hình bất động sản không được để trống")
    private ListingCategory category;

    @NotBlank(message = "Vui lòng chọn địa chỉ từ Vietmap")
    private String refId;
    @jakarta.validation.constraints.Size(max=255)
    private String addressNote;

    private String description;
    private String buildingRules;
    @Builder.Default
    private Integer motorbikeParkingCapacity = 0;
    @Builder.Default
    private Integer carParkingCapacity = 0;
    private String coverImageId;
    private List<CreateBranchChargeRequest> defaultCharges;
    private List<String> buildingAmenityCodes;
}
