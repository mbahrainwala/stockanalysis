package com.stocks.tracker.service;

public class StockLookupException extends RuntimeException {
    public StockLookupException(String message) {
        super(message);
    }

    public StockLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
