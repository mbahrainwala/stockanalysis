package com.stocks.tracker.service;

/**
 * Rough token counts without a tokenizer. Prices and tickers tokenize poorly (often 2-3 characters
 * per token), so this deliberately overestimates compared with the ~4 characters/token of prose.
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.0;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
