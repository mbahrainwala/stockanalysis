package com.stocks.tracker.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "holding", uniqueConstraints = @UniqueConstraint(columnNames = {"trading_account_id", "stock_id"}))
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "trading_account_id", nullable = false)
    private TradingAccount tradingAccount;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal shares = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4)
    private BigDecimal averageCost;

    /** Individual purchases; shares and averageCost are kept as their totals. */
    @OneToMany(mappedBy = "holding", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseLot> lots = new ArrayList<>();

    public Holding() {
    }

    public Holding(TradingAccount tradingAccount, Stock stock, BigDecimal shares, BigDecimal averageCost) {
        this.tradingAccount = tradingAccount;
        this.stock = stock;
        this.shares = shares;
        this.averageCost = averageCost;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TradingAccount getTradingAccount() {
        return tradingAccount;
    }

    public void setTradingAccount(TradingAccount tradingAccount) {
        this.tradingAccount = tradingAccount;
    }

    public Stock getStock() {
        return stock;
    }

    public void setStock(Stock stock) {
        this.stock = stock;
    }

    public BigDecimal getShares() {
        return shares;
    }

    public void setShares(BigDecimal shares) {
        this.shares = shares;
    }

    public List<PurchaseLot> getLots() {
        return lots;
    }

    public BigDecimal getAverageCost() {
        return averageCost;
    }

    public void setAverageCost(BigDecimal averageCost) {
        this.averageCost = averageCost;
    }
}
