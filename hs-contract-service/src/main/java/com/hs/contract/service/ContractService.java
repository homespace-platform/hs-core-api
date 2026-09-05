package com.hs.contract.service;

import com.hs.common.dto.PageResponse;
import com.hs.contract.dto.request.CreateContractDraftRequest;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.dto.response.ContractResponse;
import com.hs.contract.dto.response.ContractRevisionResponse;
import com.hs.contract.model.constant.ContractStatus;

import java.util.List;

public interface ContractService {

    ContractResponse createDraft(CreateContractDraftRequest request);

    ContractResponse getContract(String contractId);

    PageResponse<ContractResponse> getContractsForCurrentUser(String userId, ContractStatus status, int page, int size);

    List<ContractResponse> getContractsForCurrentUser(ContractStatus status);

    ContractRevisionResponse getLatestRevision(String contractId);

    ContractRevisionResponse updateRevision(String contractId, UpdateContractRevisionRequest request);

    ContractDocumentResponse triggerPreview(String contractId);

    ContractDocumentResponse getDocument(String documentId);

    List<ContractDocumentResponse> getDocumentsByContract(String contractId);

    PageResponse<ContractResponse> getAllContractsForAdmin(ContractStatus status, String keyword, int page, int size);

    List<ContractResponse> getAllContractsForAdmin(ContractStatus status, String keyword);

    ContractResponse getContractForAdmin(String contractId);

    ContractRevisionResponse getLatestRevisionForAdmin(String contractId);

    List<ContractDocumentResponse> getDocumentsForAdmin(String contractId);
}

