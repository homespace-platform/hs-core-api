package com.hs.contract.dto.response;

import com.hs.contract.model.constant.ContractDocumentType;
import com.hs.contract.model.constant.DocumentGenerationStatus;
import com.hs.contract.model.constant.DocumentPurpose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDocumentResponse {
    private String id;
    private String contractId;
    private String revisionId;
    private String templateVersionId;
    private ContractDocumentType documentType;
    private DocumentPurpose purpose;
    private String storageObjectId;
    private String fileName;
    private Long fileSize;
    private DocumentGenerationStatus status;
    private String errorMessage;
    private Instant generatedAt;
    private String viewUrl;
    private String downloadUrl;
}
