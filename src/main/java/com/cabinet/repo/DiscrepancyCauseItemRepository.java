package com.cabinet.repo;

import com.cabinet.domain.DiscrepancyCauseItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiscrepancyCauseItemRepository extends JpaRepository<DiscrepancyCauseItem, Long> {
    List<DiscrepancyCauseItem> findByDiscrepancyIdOrderByOccurredAtAscIdAsc(Long discrepancyId);
}
