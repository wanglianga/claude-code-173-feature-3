package com.cabinet.repo;

import com.cabinet.domain.DiscrepancyOrder;
import com.cabinet.domain.enums.DiscrepancyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscrepancyOrderRepository extends JpaRepository<DiscrepancyOrder, Long> {
    Optional<DiscrepancyOrder> findByDiscrepancyNo(String discrepancyNo);

    List<DiscrepancyOrder> findByCabinetIdOrderByIdDesc(Long cabinetId);

    List<DiscrepancyOrder> findByBatchIdOrderByIdDesc(Long batchId);

    List<DiscrepancyOrder> findByStatusInOrderByIdDesc(List<DiscrepancyStatus> statuses);

    /** 同盘点行是否已有差异单（重复盘点接回原单，而不是新建） */
    boolean existsByStocktakeIdAndChannelIdAndBatchId(Long stocktakeId, Long channelId, Long batchId);
}
