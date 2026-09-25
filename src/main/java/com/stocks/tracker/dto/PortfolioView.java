package com.stocks.tracker.dto;

import com.stocks.tracker.model.TradingAccount;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Grand totals are broken down per currency (see {@link #grandTotalsByCurrency()})
 * rather than a single sum, since holdings span USD, CAD and INR.
 */
public record PortfolioView(List<TradingAccount> accounts, List<PortfolioRow> rows,
                             Map<String, BigDecimal> grandTotalsByCurrency) {
}
