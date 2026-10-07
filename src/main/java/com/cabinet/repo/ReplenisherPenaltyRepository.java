package com.cabinet.repo;

import com.cabinet.domain.ReplenisherPenalty;
import com.cabinet.domain.enums.PenaltyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplenisherPenaltyRepository extends JpaRepository<ReplenisherPenalty, Long> {

    List<ReplenisherPenalty> findByReplenisherIdAndStatus(Long replenisherId, PenaltyStatus status);

    List<ReplenisherPenalty> findByDiscrepancyId(Long discrepancyId);

    List<ReplenisherPenalty> findByStatus(PenaltyStatus status);
}
