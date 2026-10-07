package com.cabinet.repo;

import com.cabinet.domain.FoodSafetyReview;
import com.cabinet.domain.enums.FoodSafetyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FoodSafetyReviewRepository extends JpaRepository<FoodSafetyReview, Long> {

    Optional<FoodSafetyReview> findByReviewNo(String reviewNo);

    List<FoodSafetyReview> findByDiscrepancyIdOrderByCreatedAtDesc(Long discrepancyId);

    List<FoodSafetyReview> findByBatchIdAndStatus(Long batchId, FoodSafetyStatus status);
}
