package com.stocks.tracker.repository;

import com.stocks.tracker.model.Market;
import com.stocks.tracker.model.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findBySymbolIgnoreCaseAndMarket(String symbol, Market market);
}
