package com.stocks.tracker.dto;

/** A search hit when looking up a stock by company name; symbol has no exchange suffix. */
public record SymbolMatch(String symbol, String name, String exchange) {
}
