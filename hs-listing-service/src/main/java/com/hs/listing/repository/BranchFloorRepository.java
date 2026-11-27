package com.hs.listing.repository;

import com.hs.listing.model.BranchFloor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BranchFloorRepository extends JpaRepository<BranchFloor, String> {
    List<BranchFloor> findAllByBranchIdOrderByPosition(String branchId);
}
