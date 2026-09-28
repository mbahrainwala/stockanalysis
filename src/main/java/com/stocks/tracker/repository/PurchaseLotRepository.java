package com.stocks.tracker.repository;

import com.stocks.tracker.model.PurchaseLot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseLotRepository extends JpaRepository<PurchaseLot, Long> {

    List<PurchaseLot> findAllByHolding_TradingAccount_Owner_IdAndHolding_Stock_IdOrderByPurchasedOnAscIdAsc(Long ownerId, Long stockId);

    Optional<PurchaseLot> findByIdAndHolding_TradingAccount_Owner_Id(Long id, Long ownerId);
}
