package com.stocks.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up currency exchange rates from Yahoo Finance (e.g. "CADUSD=X"). Rates are cached
 * so rendering the portfolio doesn't hit the network each time; a failed lookup falls back
 * to the last known rate, and is not retried for a short while.
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final long TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final long FAILURE_RETRY_MS = Duration.ofMinutes(2).toMillis();

    private record Cached(BigDecimal rate, long fetchedAt, boolean failed) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final RestClient restClient;

    public ExchangeRateService(RestClient.Builder restClientBuilder) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = restClientBuilder
                .baseUrl("https://query1.finance.yahoo.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; StockTracker/1.0)")
                .requestFactory(factory)
                .build();
    }

    /** Units of {@code to} per one unit of {@code from}; null if the rate is unavailable. */
    public BigDecimal rate(String from, String to) {
        if (from == null || to == null) {
            return null;
        }
        if (from.equalsIgnoreCase(to)) {
            return BigDecimal.ONE;
        }
        String pair = from.toUpperCase() + to.toUpperCase();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(pair);
        if (cached != null && now - cached.fetchedAt() < (cached.failed() ? FAILURE_RETRY_MS : TTL_MS)) {
            return cached.rate();
        }
        BigDecimal fresh = fetch(pair);
        if (fresh != null) {
            cache.put(pair, new Cached(fresh, now, false));
            return fresh;
        }
        // Keep serving the last good rate (if any) while the provider is unreachable.
        BigDecimal stale = cached == null ? null : cached.rate();
        cache.put(pair, new Cached(stale, now, true));
        return stale;
    }

    /** Forgets cached rates so the next lookup fetches fresh ones (stale values are still kept as fallback). */
    public void invalidate() {
        cache.replaceAll((k, v) -> new Cached(v.rate(), 0, false));
    }

    private BigDecimal fetch(String pair) {
        try {
            JsonNode root = restClient.get().uri("/v8/finance/chart/" + pair + "=X").retrieve().body(JsonNode.class);
            JsonNode price = root == null ? null : root.path("chart").path("result").path(0).path("meta").path("regularMarketPrice");
            if (price == null || price.isMissingNode() || price.isNull() || price.asDouble() <= 0) {
                log.warn("No exchange rate available for {}", pair);
                return null;
            }
            return BigDecimal.valueOf(price.asDouble());
        } catch (Exception e) {
            log.warn("Could not fetch exchange rate {}: {}", pair, e.getMessage());
            return null;
        }
    }
}
