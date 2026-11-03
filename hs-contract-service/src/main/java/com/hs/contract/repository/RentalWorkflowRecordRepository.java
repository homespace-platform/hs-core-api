package com.hs.contract.repository;
import com.hs.contract.model.RentalWorkflowRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface RentalWorkflowRecordRepository extends JpaRepository<RentalWorkflowRecord,String> {
    List<RentalWorkflowRecord> findByRentalRequestId(String rentalRequestId);
}
