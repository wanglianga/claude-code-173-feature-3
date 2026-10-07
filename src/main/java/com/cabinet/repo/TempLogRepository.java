package com.cabinet.repo;

import com.cabinet.domain.TempLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TempLogRepository extends JpaRepository<TempLog, Long> {
    List<TempLog> findTop200ByCabinetIdOrderByRecordedAtDesc(Long cabinetId);
    long countByCabinetIdAndAlarmTrueAndRecordedAtAfter(Long cabinetId, LocalDateTime after);
    List<TempLog> findByCabinetIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long cabinetId, LocalDateTime from, LocalDateTime to);
}
