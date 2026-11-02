package com.hs.payment.repository;
import com.hs.payment.model.DisputeCaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface DisputeCaseEventRepository extends JpaRepository<DisputeCaseEvent,String> {
    List<DisputeCaseEvent> findByCaseIdOrderByRevisionAsc(String caseId);
}
