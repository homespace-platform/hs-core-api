package com.hs.contract.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTemplateVersionRequest {

    @NotBlank(message = "storageObjectId file Word mẫu không được để trống")
    private String storageObjectId;

    private String originalFileName;
}
