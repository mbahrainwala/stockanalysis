package com.stocks.tracker.repository;

import com.stocks.tracker.model.TradingAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradingAccountRepository extends JpaRepository<TradingAccount, Long> {
    Optional<TradingAccount> findByNameIgnoreCase(String name);
}
