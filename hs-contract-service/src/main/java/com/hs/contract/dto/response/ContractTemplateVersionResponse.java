package com.hs.contract.dto.response;

import com.hs.contract.model.constant.TemplateVersionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractTemplateVersionResponse {
    private String id;
    private String templateId;
    private Integer versionNumber;
    private String storageObjectId;
    private String originalFileName;
    private TemplateVersionStatus status;
    private List<String> placeholders;
    private List<String> validationWarnings;
    private Instant publishedAt;
    private String publishedBy;
    private Instant createdAt;
}
