package com.stocks.tracker.dto;

import com.stocks.tracker.model.Market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of the portfolio matrix: a stock, its per-account share counts,
 * and totals across all accounts. The Total column is in the stock's own
 * currency (see {@link #getCurrency()}); per-account values and average costs are in
 * each account's currency, and gain/loss is converted to USD so it can be summed.
 */
public class PortfolioRow {

    private final Long stockId;
    private final String symbol;
    private final Market market;
    private final String companyName;
    private final String currency;
    private final BigDecimal currentPrice;
    private final LocalDateTime priceUpdatedAt;
    private final Map<Long, BigDecimal> sharesByAccountId = new LinkedHashMap<>();
    private final Map<Long, BigDecimal> valueByAccountId = new LinkedHashMap<>();
    private final Map<Long, String> currencySymbolByAccountId = new LinkedHashMap<>();
    private final Map<Long, BigDecimal> averageCostByAccountId = new LinkedHashMap<>();
    private BigDecimal totalShares = BigDecimal.ZERO;
    private BigDecimal totalValue = BigDecimal.ZERO;
    // Only holdings with a known cost and a usable exchange rate count towards gain/loss.
    private BigDecimal gainLossUsd;
    private BigDecimal costUsd = BigDecimal.ZERO;

    public PortfolioRow(Long stockId, String symbol, Market market, String companyName, String currency, BigDecimal currentPrice,
                        LocalDateTime priceUpdatedAt) {
        this.stockId = stockId;
        this.symbol = symbol;
        this.market = market;
        this.companyName = companyName;
        this.currency = currency;
        this.currentPrice = currentPrice;
        this.priceUpdatedAt = priceUpdatedAt;
    }

    /**
     * @param nativeValue   position value in the stock's own currency (feeds the Total column)
     * @param displayValue  position value in the account's currency
     * @param averageCost   average purchase price per share in the account's currency, may be null
     * @param gainLossUsd   gain/loss converted to USD, null when it can't be computed
     * @param costUsd       cost basis converted to USD (used for the gain/loss percentage)
     */
    public void putHolding(Long accountId, BigDecimal shares, BigDecimal nativeValue, BigDecimal displayValue,
                           String displaySymbol, BigDecimal averageCost, BigDecimal gainLossUsd, BigDecimal costUsd) {
        sharesByAccountId.put(accountId, shares);
        valueByAccountId.put(accountId, displayValue);
        currencySymbolByAccountId.put(accountId, displaySymbol);
        if (averageCost != null && averageCost.signum() > 0) {
            averageCostByAccountId.put(accountId, averageCost);
        }
        totalShares = totalShares.add(shares);
        totalValue = totalValue.add(nativeValue);
        if (gainLossUsd != null) {
            this.gainLossUsd = (this.gainLossUsd == null ? BigDecimal.ZERO : this.gainLossUsd).add(gainLossUsd);
            this.costUsd = this.costUsd.add(costUsd);
        }
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

    /** When the price was last refreshed; null if it never has been. */
    public LocalDateTime getPriceUpdatedAt() {
        return priceUpdatedAt;
    }

    public Map<Long, BigDecimal> getSharesByAccountId() {
        return sharesByAccountId;
    }

    /** Position value per account, in that account's currency. */
    public Map<Long, BigDecimal> getValueByAccountId() {
        return valueByAccountId;
    }

    public Map<Long, String> getCurrencySymbolByAccountId() {
        return currencySymbolByAccountId;
    }

    /** Average purchase price per account; accounts without a known cost are absent. */
    public Map<Long, BigDecimal> getAverageCostByAccountId() {
        return averageCostByAccountId;
    }

    public BigDecimal getTotalShares() {
        return totalShares;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    /** Total gain/loss across accounts in USD, or null when unknown. */
    public BigDecimal getGainLossUsd() {
        return gainLossUsd == null ? null : gainLossUsd.setScale(2, RoundingMode.HALF_UP);
    }

    /** Cost basis of the holdings counted in {@link #getGainLossUsd()}, in USD. */
    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public BigDecimal getGainLossPercent() {
        return percent(gainLossUsd, costUsd);
    }

    static BigDecimal percent(BigDecimal gain, BigDecimal cost) {
        if (gain == null || cost == null || cost.signum() <= 0) {
            return null;
        }
        return gain.multiply(BigDecimal.valueOf(100)).divide(cost, 1, RoundingMode.HALF_UP);
    }
}
