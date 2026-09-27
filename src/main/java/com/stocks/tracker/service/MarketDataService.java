package com.stocks.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocks.tracker.dto.PricePoint;
import com.stocks.tracker.dto.QuoteResult;
import com.stocks.tracker.dto.SymbolMatch;
import com.stocks.tracker.model.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks up a live quote for a ticker symbol using Yahoo Finance's public,
 * unauthenticated chart endpoint. No API key is required, but the endpoint
 * is unofficial and may change or rate-limit without notice.
 *
 * Yahoo identifies non-US listings by suffixing the bare ticker with an
 * exchange code (e.g. "RELIANCE.NS" for NSE, "TD.TO" for the TSX), which is
 * what {@link Market#toYahooSymbol(String)} builds.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final RestClient restClient;

    public MarketDataService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://query1.finance.yahoo.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; StockTracker/1.0)")
                .build();
    }

    public QuoteResult fetchQuote(String rawSymbol, Market market) {
        String baseSymbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase();
        if (baseSymbol.isEmpty()) {
            throw new StockLookupException("Stock symbol must not be empty");
        }
        if (market == null) {
            throw new StockLookupException("Market must be specified for symbol " + baseSymbol);
        }

        String yahooSymbol = market.toYahooSymbol(baseSymbol);
        String path = "/v8/finance/chart/" + URLEncoderUtil.encode(yahooSymbol);

        JsonNode root;
        try {
            root = restClient.get()
                    .uri(path)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Failed to fetch quote for {}: {}", yahooSymbol, e.getMessage());
            throw new StockLookupException("Could not reach market data provider for symbol " + baseSymbol, e);
        }

        if (root == null) {
            throw new StockLookupException("Empty response from market data provider for symbol " + baseSymbol);
        }

        JsonNode error = root.path("chart").path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new StockLookupException("Symbol not found on " + market.getLabel() + ": " + baseSymbol);
        }

        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            throw new StockLookupException("Symbol not found on " + market.getLabel() + ": " + baseSymbol);
        }

        JsonNode meta = result.get(0).path("meta");
        if (meta.isMissingNode()) {
            throw new StockLookupException("Unexpected response for symbol: " + baseSymbol);
        }

        BigDecimal price = meta.has("regularMarketPrice") && !meta.get("regularMarketPrice").isNull()
                ? BigDecimal.valueOf(meta.get("regularMarketPrice").asDouble())
                : null;

        if (price == null) {
            throw new StockLookupException("No price available for symbol: " + baseSymbol);
        }

        String companyName = meta.has("longName") ? meta.get("longName").asText()
                : meta.has("shortName") ? meta.get("shortName").asText()
                : baseSymbol;
        String currency = meta.has("currency") && !meta.get("currency").isNull()
                ? meta.get("currency").asText()
                : market.getDefaultCurrency();

        return new QuoteResult(baseSymbol, market, companyName, price, currency);
    }

    /** Chart ranges the UI offers; longer ones use weekly closes to keep the series small. */
    public static final List<String> HISTORY_RANGES = List.of("3mo", "6mo", "1y", "5y");

    /** Closing prices over the given range (one of {@link #HISTORY_RANGES}), oldest first. Empty if unavailable. */
    public List<PricePoint> fetchHistory(String rawSymbol, Market market, String range) {
        if (!HISTORY_RANGES.contains(range)) {
            throw new IllegalArgumentException("Unsupported range: " + range);
        }
        String interval = range.equals("5y") ? "1wk" : "1d";
        String yahooSymbol = market.toYahooSymbol(rawSymbol);
        List<PricePoint> points = new ArrayList<>();
        try {
            JsonNode root = restClient.get()
                    .uri("/v8/finance/chart/" + URLEncoderUtil.encode(yahooSymbol) + "?range=" + range + "&interval=" + interval)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode result = root == null ? null : root.path("chart").path("result").path(0);
            if (result == null || result.isMissingNode()) {
                return points;
            }
            JsonNode times = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
            for (int i = 0; i < times.size() && i < closes.size(); i++) {
                if (closes.get(i).isNull()) {
                    continue;
                }
                LocalDate date = Instant.ofEpochSecond(times.get(i).asLong()).atZone(ZoneOffset.UTC).toLocalDate();
                points.add(new PricePoint(date, BigDecimal.valueOf(closes.get(i).asDouble())));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch price history for {}: {}", yahooSymbol, e.getMessage());
        }
        return points;
    }

    /**
     * Searches Yahoo Finance by company name (or partial symbol) and returns
     * equities/ETFs listed on the given market, with the exchange suffix stripped.
     */
    public List<SymbolMatch> searchByName(String query, Market market) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            throw new StockLookupException("Enter at least 2 characters of the company name");
        }
        if (market == null) {
            throw new StockLookupException("Market must be specified");
        }

        JsonNode root;
        try {
            root = restClient.get()
                    .uri("/v1/finance/search?q={q}&quotesCount=20&newsCount=0&listsCount=0", q)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Failed to search for '{}': {}", q, e.getMessage());
            throw new StockLookupException("Could not reach market data provider", e);
        }

        List<SymbolMatch> matches = new ArrayList<>();
        if (root == null || !root.path("quotes").isArray()) {
            return matches;
        }
        String suffix = market.getYahooSuffix().toUpperCase();
        for (JsonNode quote : root.get("quotes")) {
            String type = quote.path("quoteType").asText("");
            if (!type.equals("EQUITY") && !type.equals("ETF")) {
                continue;
            }
            String symbol = quote.path("symbol").asText("").toUpperCase();
            if (symbol.isEmpty()) {
                continue;
            }
            String base;
            if (suffix.isEmpty()) {
                if (symbol.contains(".")) {
                    continue;
                }
                base = symbol;
            } else {
                if (!symbol.endsWith(suffix)) {
                    continue;
                }
                base = symbol.substring(0, symbol.length() - suffix.length());
            }
            String name = quote.hasNonNull("longname") ? quote.get("longname").asText()
                    : quote.path("shortname").asText(base);
            matches.add(new SymbolMatch(base, name, quote.path("exchDisp").asText("")));
        }
        return matches;
    }

    private static final class URLEncoderUtil {
        static String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
