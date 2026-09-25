package com.stocks.tracker.model;

/**
 * Exchange a stock is listed on. The suffix is what Yahoo Finance expects
 * appended to the bare ticker (e.g. "RELIANCE" -> "RELIANCE.NS").
 */
public enum Market {

    US("United States", "", "USD", "$"),
    CANADA_TSX("Canada (TSX)", ".TO", "CAD", "C$"),
    CANADA_TSXV("Canada (TSX-V)", ".V", "CAD", "C$"),
    INDIA_NSE("India (NSE)", ".NS", "INR", "₹"),
    INDIA_BSE("India (BSE)", ".BO", "INR", "₹");

    private final String label;
    private final String yahooSuffix;
    private final String defaultCurrency;
    private final String currencySymbol;

    Market(String label, String yahooSuffix, String defaultCurrency, String currencySymbol) {
        this.label = label;
        this.yahooSuffix = yahooSuffix;
        this.defaultCurrency = defaultCurrency;
        this.currencySymbol = currencySymbol;
    }

    public String getLabel() {
        return label;
    }

    public String getYahooSuffix() {
        return yahooSuffix;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public String getCurrencySymbol() {
        return currencySymbol;
    }

    public String toYahooSymbol(String baseSymbol) {
        return baseSymbol.trim().toUpperCase() + yahooSuffix;
    }
}
