package com.cabinet.repo;

import com.cabinet.domain.DiscrepancyFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiscrepancyFollowUpRepository extends JpaRepository<DiscrepancyFollowUp, Long> {

    List<DiscrepancyFollowUp> findByDiscrepancyIdOrderByCreatedAtAsc(Long discrepancyId);
}
