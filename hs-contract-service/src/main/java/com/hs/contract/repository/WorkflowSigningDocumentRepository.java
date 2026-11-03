package com.hs.contract.repository;
import com.hs.contract.model.WorkflowSigningDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface WorkflowSigningDocumentRepository extends JpaRepository<WorkflowSigningDocument,String>{
    Optional<WorkflowSigningDocument> findByWorkflowIdAndContentHash(String workflowId,String contentHash);
    Optional<WorkflowSigningDocument> findBySigningContractId(String id);
    List<WorkflowSigningDocument> findByWorkflowIdOrderByPreparedAtDesc(String workflowId);
}
