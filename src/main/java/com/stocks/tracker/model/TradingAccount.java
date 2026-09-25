package com.stocks.tracker.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trading_account")
public class TradingAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String name;

    private String broker;

    /** Currency this account is denominated in; null means each stock's own currency (no conversion). */
    @Column(length = 3)
    private String currency;

    @OneToMany(mappedBy = "tradingAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Holding> holdings = new ArrayList<>();

    public TradingAccount() {
    }

    public TradingAccount(String name, String broker) {
        this.name = name;
        this.broker = broker;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBroker() {
        return broker;
    }

    public void setBroker(String broker) {
        this.broker = broker;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public List<Holding> getHoldings() {
        return holdings;
    }

    public void setHoldings(List<Holding> holdings) {
        this.holdings = holdings;
    }
}
