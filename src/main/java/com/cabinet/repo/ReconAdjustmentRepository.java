package com.cabinet.repo;

import com.cabinet.domain.ReconAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconAdjustmentRepository extends JpaRepository<ReconAdjustment, Long> {

    List<ReconAdjustment> findByDiscrepancyId(Long discrepancyId);

    List<ReconAdjustment> findBySupplierIdAndAppliedFalseOrderByCreatedAtAsc(Long supplierId);

    List<ReconAdjustment> findByBatchId(Long batchId);
}
