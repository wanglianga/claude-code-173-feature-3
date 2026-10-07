package com.cabinet.repo;

import com.cabinet.domain.TempLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TempLogRepository extends JpaRepository<TempLog, Long> {
    List<TempLog> findTop200ByCabinetIdOrderByRecordedAtDesc(Long cabinetId);
    long countByCabinetIdAndAlarmTrueAndRecordedAtAfter(Long cabinetId, LocalDateTime after);

    /** 温控曲线：差异调查时间窗内某柜机的温度记录（含越限标记） */
    List<TempLog> findByCabinetIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long cabinetId, LocalDateTime start, LocalDateTime end);

    /** 时间窗内越限温度记录（用于判定温度异常损耗） */
    List<TempLog> findByCabinetIdAndAlarmTrueAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long cabinetId, LocalDateTime start, LocalDateTime end);
}
