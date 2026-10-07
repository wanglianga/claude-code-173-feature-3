package com.cabinet.repo;

import com.cabinet.domain.DiscrepancyAllocation;
import com.cabinet.domain.enums.AllocationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiscrepancyAllocationRepository extends JpaRepository<DiscrepancyAllocation, Long> {

    List<DiscrepancyAllocation> findByDiscrepancyIdOrderByOccurredAtAscIdAsc(Long discrepancyId);

    List<DiscrepancyAllocation> findByDiscrepancyIdAndStatus(Long discrepancyId, AllocationStatus status);
}
