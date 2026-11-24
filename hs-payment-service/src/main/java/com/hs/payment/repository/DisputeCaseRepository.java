package com.hs.payment.repository;
import com.hs.payment.model.DisputeCase;
import com.hs.payment.model.constant.DisputeCaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.util.*;
public interface DisputeCaseRepository extends JpaRepository<DisputeCase,String>, JpaSpecificationExecutor<DisputeCase> {
    Optional<DisputeCase> findByActiveKey(String activeKey);
    boolean existsByRentalRequestIdAndActiveKeyIsNotNull(String rentalRequestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from DisputeCase c where c.id=:id")
    Optional<DisputeCase> findByIdForUpdate(@Param("id") String id);
    Page<DisputeCase> findByStatus(DisputeCaseStatus status,Pageable pageable);
    List<DisputeCase> findBySubjectTypeAndRentalRequestIdOrderByOpenedAtDesc(String subjectType,String rentalRequestId);
    List<DisputeCase> findBySubjectTypeAndRentalRequestIdOrderByOpenedAtDesc(String subjectType,String rentalRequestId,Pageable pageable);
    List<DisputeCase> findBySubjectTypeAndRentalRequestIdAndStatusNotOrderByOpenedAtDesc(String subjectType,String rentalRequestId,DisputeCaseStatus status);
}
