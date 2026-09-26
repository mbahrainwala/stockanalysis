package com.stocks.tracker.model;

import java.util.List;
import java.util.Locale;

/** Currencies an account can be denominated in, and their display symbols. */
public final class Currencies {

    public static final String BASE = "USD";
    public static final List<String> SUPPORTED = List.of("USD", "CAD", "INR");

    private Currencies() {
    }

    public static String symbol(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "USD" -> "$";
            case "CAD" -> "C$";
            case "INR" -> "₹";
            default -> code + " ";
        };
    }

    /** Blank means "the stock's own currency" (no conversion) and is returned as null. */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.contains(c)) {
            throw new IllegalArgumentException("Unsupported currency: " + code);
        }
        return c;
    }
}
