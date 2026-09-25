package com.stocks.tracker.dto;

import com.stocks.tracker.model.Market;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of the portfolio matrix: a stock, its per-account share counts,
 * and totals across all accounts. Values are in the stock's own currency
 * (see {@link #getCurrency()}) since a US, Canadian and Indian holding
 * cannot be summed directly.
 */
public class PortfolioRow {

    private final Long stockId;
    private final String symbol;
    private final Market market;
    private final String companyName;
    private final String currency;
    private final BigDecimal currentPrice;
    private final Map<Long, BigDecimal> sharesByAccountId = new LinkedHashMap<>();
    private final Map<Long, BigDecimal> valueByAccountId = new LinkedHashMap<>();
    private BigDecimal totalShares = BigDecimal.ZERO;
    private BigDecimal totalValue = BigDecimal.ZERO;

    public PortfolioRow(Long stockId, String symbol, Market market, String companyName, String currency, BigDecimal currentPrice) {
        this.stockId = stockId;
        this.symbol = symbol;
        this.market = market;
        this.companyName = companyName;
        this.currency = currency;
        this.currentPrice = currentPrice;
    }

    public void putAccountShares(Long accountId, BigDecimal shares, BigDecimal value) {
        sharesByAccountId.put(accountId, shares);
        valueByAccountId.put(accountId, value);
        totalShares = totalShares.add(shares);
        totalValue = totalValue.add(value);
    }

    public Long getStockId() {
        return stockId;
    }

    public String getSymbol() {
        return symbol;
    }

    public Market getMarket() {
        return market;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public Map<Long, BigDecimal> getSharesByAccountId() {
        return sharesByAccountId;
    }

    public Map<Long, BigDecimal> getValueByAccountId() {
        return valueByAccountId;
    }

    public BigDecimal getTotalShares() {
        return totalShares;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }
}
