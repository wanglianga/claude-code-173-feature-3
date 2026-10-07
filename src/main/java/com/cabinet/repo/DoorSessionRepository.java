package com.cabinet.repo;

import com.cabinet.domain.DoorSession;
import com.cabinet.domain.enums.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DoorSessionRepository extends JpaRepository<DoorSession, Long> {
    Optional<DoorSession> findBySessionNo(String sessionNo);
    List<DoorSession> findByCabinetIdOrderByOpenedAtDesc(Long cabinetId);
    List<DoorSession> findByCabinetCabinetNoOrderByOpenedAtDesc(String cabinetNo);
    List<DoorSession> findByUserCodeOrderByOpenedAtDesc(String userCode);
    List<DoorSession> findByStatus(SessionStatus status);

    /** 差异调查时间窗内某柜机的开门记录 */
    List<DoorSession> findByCabinetIdAndOpenedAtBetweenOrderByOpenedAtAsc(
            Long cabinetId, LocalDateTime start, LocalDateTime end);
}
