package com.hs.listing.repository;
import com.hs.listing.model.RentalStartChange;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface RentalStartChangeRepository extends JpaRepository<RentalStartChange,String> {
    @org.springframework.data.jpa.repository.Query(value="select distinct on (rental_request_id) * from rental_start_changes where rental_request_id in (:ids) order by rental_request_id, requested_at desc, id desc",nativeQuery=true)
    List<RentalStartChange> findLatestForRentalRequestIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);
    List<RentalStartChange> findByRentalRequestIdOrderByRequestedAtDesc(String rentalRequestId);
}
