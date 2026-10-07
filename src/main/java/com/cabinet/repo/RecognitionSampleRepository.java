package com.cabinet.repo;

import com.cabinet.domain.RecognitionSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecognitionSampleRepository extends JpaRepository<RecognitionSample, Long> {

    List<RecognitionSample> findByDiscrepancyId(Long discrepancyId);

    List<RecognitionSample> findByReviewedFalse();
}
