package com.cabinet.repo;

import com.cabinet.domain.RestockItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestockItemRepository extends JpaRepository<RestockItem, Long> {
    List<RestockItem> findByRestockOrderId(Long orderId);
    List<RestockItem> findByBatchIdOrderByIdAsc(Long batchId);
}
