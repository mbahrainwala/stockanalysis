package com.stocks.tracker.repository;

import com.stocks.tracker.model.SpeculationEntry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpeculationEntryRepository extends JpaRepository<SpeculationEntry, Long> {

    @EntityGraph(attributePaths = "stock")
    List<SpeculationEntry> findAllByOwnerIdOrderByAddedAtDesc(Long ownerId);

    boolean existsByOwnerIdAndStockId(Long ownerId, Long stockId);

    long countByOwnerId(Long ownerId);
}
