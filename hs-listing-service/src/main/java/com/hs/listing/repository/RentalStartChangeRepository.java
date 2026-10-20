package com.hs.listing.repository;
import com.hs.listing.model.RentalStartChange;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface RentalStartChangeRepository extends JpaRepository<RentalStartChange,String> {
    List<RentalStartChange> findByRentalRequestIdOrderByRequestedAtDesc(String rentalRequestId);
}
