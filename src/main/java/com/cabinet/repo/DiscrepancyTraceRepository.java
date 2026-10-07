package com.cabinet.repo;

import com.cabinet.domain.DiscrepancyTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiscrepancyTraceRepository extends JpaRepository<DiscrepancyTrace, Long> {
    List<DiscrepancyTrace> findByDiscrepancyIdOrderByIdAsc(Long discrepancyId);
}
