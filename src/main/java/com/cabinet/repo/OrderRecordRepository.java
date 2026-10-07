package com.cabinet.repo;

import com.cabinet.domain.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {
    Optional<OrderRecord> findByOrderNo(String orderNo);
    Optional<OrderRecord> findBySessionId(Long sessionId);
    List<OrderRecord> findByCabinetId(Long cabinetId);
    List<OrderRecord> findByCabinetIdIn(List<Long> cabinetIds);

    /** 差异调查时间窗内某柜机的销售订单（含已结算/已扣款订单，用于漏扣研判） */
    List<OrderRecord> findByCabinetIdAndChargedAtBetweenOrderByIdAsc(
            Long cabinetId, LocalDateTime start, LocalDateTime end);
}
