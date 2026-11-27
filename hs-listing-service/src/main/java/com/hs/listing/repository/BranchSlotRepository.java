package com.hs.listing.repository;

import com.hs.listing.model.BranchSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BranchSlotRepository extends JpaRepository<BranchSlot, String> {
    List<BranchSlot> findAllByBranchIdOrderByPosition(String branchId);
}
