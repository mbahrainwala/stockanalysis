package com.stocks.tracker.repository;

import com.stocks.tracker.model.Holding;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    @EntityGraph(attributePaths = {"tradingAccount", "stock"})
    List<Holding> findAllByTradingAccount_Owner_Id(Long ownerId);

    Optional<Holding> findByTradingAccountIdAndStockId(Long tradingAccountId, Long stockId);

    boolean existsByTradingAccount_Owner_IdAndStockId(Long ownerId, Long stockId);
}
