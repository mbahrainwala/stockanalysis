package com.stocks.tracker.repository;

import com.stocks.tracker.model.SavedAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SavedAnalysisRepository extends JpaRepository<SavedAnalysis, Long> {

    /** Listing view that leaves out the large prompt/response text. */
    interface Summary {
        Long getId();

        LocalDateTime getCreatedAt();

        String getModel();

        long getDurationMs();
    }

    List<Summary> findAllByOrderByCreatedAtDesc();
}
