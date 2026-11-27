package com.hs.listing.repository;

import com.hs.listing.model.BranchRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BranchRowRepository extends JpaRepository<BranchRow, String> {
    List<BranchRow> findAllByFloorBranchIdOrderByPosition(String branchId);
}
