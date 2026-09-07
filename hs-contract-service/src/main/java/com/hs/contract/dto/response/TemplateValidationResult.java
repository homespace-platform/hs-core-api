package com.hs.contract.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateValidationResult {
    private boolean valid;

    @Builder.Default
    private List<String> detectedPlaceholders = new ArrayList<>();

    @Builder.Default
    private List<String> validPlaceholders = new ArrayList<>();

    @Builder.Default
    private List<String> invalidPlaceholders = new ArrayList<>();

    /** Trường bắt buộc theo loại hình BĐS của mẫu nhưng chưa xuất hiện trong file Word. */
    @Builder.Default
    private List<TemplateFieldIssue> missingRequiredFields = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
