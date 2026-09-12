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

    private String streetLine;
    private String wardCode;
    private String wardName;
    private String provinceCode;
    private String provinceName;

    @NotBlank(message = "Địa chỉ không được để trống")
    private String fullAddress;

    private String description;
    private String buildingRules;
    private List<CreateBranchChargeRequest> defaultCharges;
    private List<String> buildingAmenityCodes;
}
