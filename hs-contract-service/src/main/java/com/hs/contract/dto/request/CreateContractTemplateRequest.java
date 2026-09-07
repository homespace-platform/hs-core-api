package com.hs.contract.dto.request;

import com.hs.listing.model.constant.ListingCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateContractTemplateRequest {

    @NotBlank(message = "Tên mẫu hợp đồng không được để trống")
    @Size(max = 150, message = "Tên mẫu không quá 150 ký tự")
    private String name;

    @Size(max = 1000, message = "Mô tả không quá 1000 ký tự")
    private String description;

    @NotNull(message = "Phải chọn loại hình bất động sản áp dụng cho mẫu hợp đồng")
    private ListingCategory category;

    @NotBlank(message = "storageObjectId file Word mẫu không được để trống")
    private String storageObjectId;

    private String originalFileName;
}
