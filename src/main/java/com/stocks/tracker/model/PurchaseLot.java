package com.stocks.tracker.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One purchase of a stock in an account: a number of shares bought at a price on a date. */
@Entity
@Table(name = "purchase_lot")
public class PurchaseLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id", nullable = false)
    private Holding holding;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal shares;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false)
    private LocalDate purchasedOn;

    public PurchaseLot() {
    }

    public PurchaseLot(Holding holding, BigDecimal shares, BigDecimal price, LocalDate purchasedOn) {
        this.holding = holding;
        this.shares = shares;
        this.price = price;
        this.purchasedOn = purchasedOn;
    }

    public Long getId() {
        return id;
    }

    public Holding getHolding() {
        return holding;
    }

    public BigDecimal getShares() {
        return shares;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public LocalDate getPurchasedOn() {
        return purchasedOn;
    }

    public void setShares(BigDecimal shares) {
        this.shares = shares;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public void setPurchasedOn(LocalDate purchasedOn) {
        this.purchasedOn = purchasedOn;
    }
}
