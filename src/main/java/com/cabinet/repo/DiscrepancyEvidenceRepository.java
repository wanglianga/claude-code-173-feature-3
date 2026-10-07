package com.cabinet.repo;

import com.cabinet.domain.DiscrepancyEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiscrepancyEvidenceRepository extends JpaRepository<DiscrepancyEvidence, Long> {
    List<DiscrepancyEvidence> findByDiscrepancyIdOrderByIdAsc(Long discrepancyId);
}
