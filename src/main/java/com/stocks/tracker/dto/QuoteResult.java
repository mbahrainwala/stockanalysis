package com.stocks.tracker.dto;

import com.stocks.tracker.model.Market;

import java.math.BigDecimal;

public record QuoteResult(String symbol, Market market, String companyName, BigDecimal price, String currency) {
}
