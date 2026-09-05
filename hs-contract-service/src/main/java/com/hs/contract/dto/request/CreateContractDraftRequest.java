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
public class CreateContractDraftRequest {

    @NotBlank(message = "rentalRequestId không được để trống")
    private String rentalRequestId;

    @NotBlank(message = "templateVersionId không được để trống")
    private String templateVersionId;
}
