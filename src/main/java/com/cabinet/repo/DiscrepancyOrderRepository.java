package com.cabinet.repo;

import com.cabinet.domain.DiscrepancyOrder;
import com.cabinet.domain.enums.DiscrepancyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscrepancyOrderRepository extends JpaRepository<DiscrepancyOrder, Long> {

    Optional<DiscrepancyOrder> findByDiscrepancyNo(String discrepancyNo);

    List<DiscrepancyOrder> findByCabinetIdOrderByCreatedAtDesc(Long cabinetId);

    List<DiscrepancyOrder> findByBatchIdOrderByCreatedAtDesc(Long batchId);

    List<DiscrepancyOrder> findByStocktakeId(Long stocktakeId);

    /** 某批次当前仍冻结结算/对账的差异单（未 RESOLVED） */
    List<DiscrepancyOrder> findByBatchIdAndSettlementFrozenTrue(Long batchId);

    List<DiscrepancyOrder> findByStatusOrderByCreatedAtDesc(DiscrepancyStatus status);
}
