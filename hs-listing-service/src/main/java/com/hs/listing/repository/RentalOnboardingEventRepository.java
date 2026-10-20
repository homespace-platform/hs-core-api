package com.hs.listing.repository;
import com.hs.listing.model.RentalOnboardingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface RentalOnboardingEventRepository extends JpaRepository<RentalOnboardingEvent,String> {
    List<RentalOnboardingEvent> findByRentalRequestIdOrderByOccurredAtDesc(String id);
}
